#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
温室网关/PLC 设备模拟器（首版演示用）。

行为：
  1. 与 Netty 接入层建立 TCP 长连接，发送 REGISTER 注册；
  2. 每 30s 心跳；
  3. 按周期上报 DATA（温度/湿度/光照/CO₂），数值由一个简化物理模型驱动：
       - 白天气温与光照按时间周期变化；
       - 遮阳网展开 -> 室内光照与得热下降；
       - 湿帘开启 -> 降温增湿；风机开启 -> 通风降温（湿帘+风机蒸发降温最强）；
  4. 收到 CONTROL 指令后驱动本地执行器动作并回 ACK（可按概率模拟失败）；
  5. 断线自动重连并重新注册（用于演示服务端离线指令缓存与补发）。

报文为 4 字节大端长度前缀 + UTF-8 JSON（与服务端 LengthFieldBasedFrameDecoder 对应）。

用法：
  python3 simulator/simulator.py                 # 默认连 127.0.0.1:8600，网关 GW-001
  python3 simulator/simulator.py --hot           # 初始高温 36℃，便于立即看到降温联动
  python3 simulator/simulator.py --fail-rate 0.5 # 50% 指令执行失败，演示重试/失败告警/联动中止
  python3 simulator/simulator.py --disconnect 60 # 每 60s 断开重连一次，演示离线缓存补发
"""
import argparse
import json
import math
import random
import socket
import struct
import sys
import threading
import time

# 大棚内执行器（SN 与服务端 DataInitializer 种子数据一致）
FAN_SN = "FAN-001"
CURTAIN_SN = "WC-001"
SHADE_SN = "SHADE-001"


class GreenhouseSimulator:
    def __init__(self, host, port, sn, interval, fail_rate, disconnect,
                 start_temp, noisy):
        self.host, self.port, self.sn = host, port, sn
        self.interval = interval
        self.fail_rate = fail_rate
        self.disconnect = disconnect
        self.noisy = noisy

        # —— 简化物理模型状态 ——
        self.temp = start_temp
        self.humi = 55.0
        self.co2 = 600.0
        # 外界环境光照：用一个 12 分钟周期模拟“昼夜”，演示时几分钟内可见遮阳收放
        self.ambient_light = 80000.0
        # 执行器状态
        self.fan_on = False
        self.curtain_open = False
        self.shade_open = False

        self.sock = None
        self.send_lock = threading.Lock()
        self.running = True
        self.connected_at = 0.0

    # ==================== 网络 ====================

    def connect_forever(self):
        while self.running:
            try:
                self.sock = socket.create_connection((self.host, self.port), timeout=10)
                self.sock.settimeout(None)
                self.connected_at = time.time()
                self.send({"type": "REGISTER", "sn": self.sn})
                print(f"[网关] 已连接 {self.host}:{self.port} 并注册为 {self.sn}", flush=True)
                threading.Thread(target=self.recv_loop, daemon=True).start()
                self.main_loop()
            except (OSError, BrokenPipeError) as e:
                print(f"[网络] 连接断开：{e}，5s 后重连...", flush=True)
                time.sleep(5)

    def recv_loop(self):
        while self.running:
            try:
                header = self.recv_exact(4)
                if header is None:
                    print("[网络] 服务端关闭连接", flush=True)
                    self.reopen()
                    return
                (length,) = struct.unpack(">I", header)
                payload = self.recv_exact(length)
                if payload is None:
                    print("[网络] 服务端关闭连接", flush=True)
                    self.reopen()
                    return
                self.handle_message(payload.decode("utf-8", "ignore"))
            except (OSError, struct.error):
                return

    def recv_exact(self, n):
        """读满 n 字节，连接关闭返回 None"""
        data = b""
        while len(data) < n:
            try:
                chunk = self.sock.recv(n - len(data))
            except OSError:
                return None
            if not chunk:
                return None
            data += chunk
        return data

    def reopen(self):
        try:
            if self.sock:
                self.sock.close()
        except OSError:
            pass
        # 触发主线程重连：主动制造一个坏 socket，main_loop 写出时会抛异常
        self.sock = None

    def send(self, obj):
        payload = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        data = struct.pack(">I", len(payload)) + payload
        with self.send_lock:
            if self.sock is None:
                raise BrokenPipeError("socket 已关闭")
            self.sock.sendall(data)

    # ==================== 报文处理 ====================

    def handle_message(self, text):
        try:
            msg = json.loads(text)
        except json.JSONDecodeError:
            print(f"[报文] 非 JSON，忽略：{text}", flush=True)
            return
        mtype = msg.get("type")
        if mtype == "REGISTER_ACK":
            return
        if mtype == "CONTROL":
            self.handle_control(msg)
            return
        print(f"[报文] 未处理类型 {mtype}: {text}", flush=True)

    def handle_control(self, msg):
        cmd_id = msg.get("commandId", "")
        sn = msg.get("deviceSn", "")
        action = msg.get("action", "")
        print(f"[指令] 收到 {cmd_id[:8]} {sn} {action}", flush=True)

        # 模拟执行器动作耗时
        time.sleep(random.uniform(0.2, 0.8))
        failed = random.random() < self.fail_rate
        if failed:
            self.send({"type": "ACK", "commandId": cmd_id, "success": False,
                       "error": "执行器过载（模拟）"})
            print(f"[指令] {cmd_id[:8]} 执行失败（模拟）", flush=True)
            return

        state = self.apply_action(sn, action)
        self.send({"type": "ACK", "commandId": cmd_id, "success": True, "state": state})
        print(f"[指令] {cmd_id[:8]} 回执成功，{sn} -> {state}", flush=True)

    def apply_action(self, sn, action):
        if sn == FAN_SN:
            self.fan_on = action == "ON"
            return "ON" if self.fan_on else "OFF"
        if sn == CURTAIN_SN:
            self.curtain_open = action == "OPEN"
            return "OPEN" if self.curtain_open else "CLOSED"
        if sn == SHADE_SN:
            self.shade_open = action == "OPEN"
            return "OPEN" if self.shade_open else "CLOSED"
        return action

    # ==================== 物理模型 + 周期上报 ====================

    def tick_physics(self, dt):
        # 外界光照：12 分钟一个昼夜周期，白天峰值约 85klux
        phase = (time.time() % 720) / 720.0
        solar = max(0.0, math.sin(phase * 2 * math.pi - math.pi / 2) * 0.5 + 0.5)
        self.ambient_light = 90000.0 * solar

        # 室内得热：太阳辐射（遮阳网削弱约 70%），目标平衡点 33℃（白天）
        shade_factor = 0.3 if self.shade_open else 1.0
        indoor_light = self.ambient_light * shade_factor
        solar_gain = (self.ambient_light / 90000.0) * shade_factor * 0.12

        # 降温：湿帘蒸发降温，叠加风机通风换热
        cooling = 0.0
        if self.curtain_open:
            cooling += 0.10
        if self.fan_on:
            cooling += 0.05
        if self.fan_on and self.curtain_open:
            cooling += 0.12  # 湿帘+风机：蒸发降温协同效应

        ambient_target = 24.0 + 11.0 * (self.ambient_light / 90000.0) * shade_factor
        d_temp = (ambient_target - self.temp) * 0.02 + solar_gain - cooling
        self.temp += d_temp * dt
        if self.noisy:
            self.temp += random.uniform(-0.08, 0.08)
        self.temp = max(5.0, min(50.0, self.temp))

        # 湿度：湿帘增湿；风机会带走湿气；自然向 55% 回归
        target_h = 55.0
        if self.curtain_open:
            target_h = 85.0
        d_h = (target_h - self.humi) * 0.05
        if self.fan_on and not self.curtain_open:
            d_h -= 0.15
        self.humi = max(20.0, min(98.0, self.humi + d_h * dt))

        # CO₂：光合 + 通风缓慢变化
        self.co2 += (-3.0 if self.fan_on else 1.0) * dt
        self.co2 = max(380.0, min(1200.0, self.co2))

        return indoor_light

    def main_loop(self):
        last_beat = 0.0
        last_report = 0.0
        while self.running:
            time.sleep(0.5)
            if self.sock is None:
                raise BrokenPipeError("等待重连")

            # 周期性主动断开，演示离线缓存与重连补发
            if self.disconnect and time.time() - self.connected_at > self.disconnect:
                print(f"[测试] 模拟网络中断，{self.disconnect}s 后重连...", flush=True)
                self.reopen()
                time.sleep(self.disconnect)
                raise BrokenPipeError("模拟断线")

            now = time.time()
            if now - last_report >= self.interval:
                last_report = now
                light = self.tick_physics(self.interval)
                self.send({"type": "DATA", "sn": self.sn, "data": {
                    "temperature": round(self.temp, 1),
                    "humidity": round(self.humi, 1),
                    "light": round(light, 0),
                    "co2": round(self.co2, 0),
                }})
                print(f"[上报] T={self.temp:.1f}℃ H={self.humi:.1f}% "
                      f"L={light:.0f}lux CO2={self.co2:.0f} | "
                      f"风机={'开' if self.fan_on else '停'} "
                      f"湿帘={'开' if self.curtain_open else '关'} "
                      f"遮阳={'展' if self.shade_open else '收'}", flush=True)

            if now - last_beat >= 30:
                last_beat = now
                self.send({"type": "HEARTBEAT", "sn": self.sn, "ts": int(now * 1000)})

    def stop(self, *_):
        self.running = False


def main():
    ap = argparse.ArgumentParser(description="温室网关/PLC 模拟器")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8600)
    ap.add_argument("--sn", default="GW-001", help="网关 SN（需与大棚 gatewaySn 一致）")
    ap.add_argument("--interval", type=float, default=5.0, help="数据上报周期秒数")
    ap.add_argument("--fail-rate", type=float, default=0.0, help="指令随机失败概率 0~1")
    ap.add_argument("--disconnect", type=float, default=0.0,
                    help=">0 时每隔该秒数主动断线重连，演示离线补发")
    ap.add_argument("--start-temp", type=float, default=35.0, help="初始棚内温度")
    ap.add_argument("--noisy", action="store_true", help="加入测量噪声")
    args = ap.parse_args()

    sim = GreenhouseSimulator(args.host, args.port, args.sn, args.interval,
                              args.fail_rate, args.disconnect, args.start_temp,
                              args.noisy)
    try:
        sim.connect_forever()
    except KeyboardInterrupt:
        print("\n模拟器退出", flush=True)
        sys.exit(0)


if __name__ == "__main__":
    main()
