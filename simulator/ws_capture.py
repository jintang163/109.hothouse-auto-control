#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
实时推送自检：验证 /ws/realtime 的 sensor / device / alarm / command 四类事件。

被动监听只能等到 sensor/device/command —— alarm 仅在告警条件发生时产生，
因此本脚本会主动制造一次「安全互锁拦截」来触发 alarm 事件：
  1. 大棚切手动模式，先停风机、关湿帘（等回执生效，避免规则引擎干扰）；
  2. 在湿帘关闭状态下直接启动风机 → 被安全互锁拦截并产生 WARN 告警；
  3. 统计监听期间捕获的事件类型，四类齐全则自检通过（退出码 0）。

前置条件：后端已启动（:8080）；设备模拟器已启动（否则捕获不到 sensor 事件，
且设备状态指令无回执，device 事件也会缺失）。自检结束后恢复原运行模式。

用法：python3 simulator/ws_capture.py [--host 127.0.0.1] [--port 8080] [--timeout 10]
"""
import argparse
import base64
import json
import os
import socket
import struct
import sys
import threading
import time
import urllib.request

EVENT_TYPES = ["sensor", "device", "alarm", "command"]
OPERATOR = "ws-capture"


class WsClient:
    """最小 WebSocket 客户端（标准库实现）：握手 + 文本帧接收 + ping/pong。"""

    def __init__(self, host, port, path="/ws/realtime"):
        self.host, self.port, self.path = host, port, path
        self.sock = None
        self.running = False
        self.thread = None
        self.lock = threading.Lock()
        self.counts = {t: 0 for t in EVENT_TYPES}
        self.last_alarm = None

    def connect(self):
        self.sock = socket.create_connection((self.host, self.port), timeout=10)
        key = base64.b64encode(os.urandom(16)).decode()
        req = (f"GET {self.path} HTTP/1.1\r\nHost: {self.host}:{self.port}\r\n"
               "Upgrade: websocket\r\nConnection: Upgrade\r\n"
               f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n")
        self.sock.sendall(req.encode())
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = self.sock.recv(1024)
            if not chunk:
                raise ConnectionError("WS 握手失败：连接被关闭")
            resp += chunk
        status_line = resp.split(b"\r\n", 1)[0].decode("utf-8", "replace")
        if " 101" not in status_line:
            raise ConnectionError(f"WS 握手失败：{status_line}")
        self.sock.settimeout(1.0)
        self.running = True
        self.thread = threading.Thread(target=self._reader, daemon=True)
        self.thread.start()

    def _recv_exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("WS 连接断开")
            buf += chunk
        return buf

    def _send_frame(self, opcode, payload=b""):
        mask = os.urandom(4)
        header = bytes([0x80 | opcode])
        n = len(payload)
        if n < 126:
            header += bytes([0x80 | n])
        elif n < 65536:
            header += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            header += bytes([0x80 | 127]) + struct.pack(">Q", n)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(header + mask + masked)

    def _reader(self):
        while self.running:
            try:
                hdr = self._recv_exact(2)
                opcode = hdr[0] & 0x0F
                length = hdr[1] & 0x7F
                if length == 126:
                    length = struct.unpack(">H", self._recv_exact(2))[0]
                elif length == 127:
                    length = struct.unpack(">Q", self._recv_exact(8))[0]
                payload = self._recv_exact(length) if length else b""
                if opcode == 0x1:  # text
                    self._on_text(payload.decode("utf-8", "replace"))
                elif opcode == 0x9:  # ping → pong
                    self._send_frame(0xA, payload)
                elif opcode == 0x8:  # close
                    break
            except socket.timeout:
                continue
            except (OSError, ConnectionError):
                break
        self.running = False

    def _on_text(self, text):
        try:
            msg = json.loads(text)
        except ValueError:
            return
        event = msg.get("event")
        with self.lock:
            if event in self.counts:
                self.counts[event] += 1
            if event == "alarm":
                self.last_alarm = (msg.get("data") or {}).get("message")

    def snapshot(self):
        with self.lock:
            return dict(self.counts), self.last_alarm

    def close(self):
        self.running = False
        try:
            self._send_frame(0x8)
            self.sock.close()
        except OSError:
            pass
        if self.thread:
            self.thread.join(timeout=2)


def http_json(host, port, method, path, body=None):
    req = urllib.request.Request(
        f"http://{host}:{port}{path}", method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read())


def wait_for(desc, cond, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if cond():
            return True
        time.sleep(0.3)
    print(f"[自检] 等待超时（{timeout}s）：{desc}", flush=True)
    return False


def main():
    ap = argparse.ArgumentParser(description="实时推送四类事件自检")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--timeout", type=int, default=10, help="各步骤等待秒数")
    args = ap.parse_args()
    host, port, timeout = args.host, args.port, args.timeout

    gh = http_json(host, port, "GET", "/api/greenhouses")["data"][0]
    gh_id, orig_mode = gh["id"], gh["mode"]
    print(f"[自检] 大棚「{gh['name']}」(id={gh_id})，当前模式 {orig_mode}，先切手动", flush=True)
    http_json(host, port, "PUT", f"/api/greenhouses/{gh_id}/mode?mode=MANUAL&operator={OPERATOR}")

    ws = WsClient(host, port)
    ws.connect()
    print("[自检] 已订阅 /ws/realtime", flush=True)

    def device_state(sn):
        data = http_json(host, port, "GET", f"/api/devices?greenhouseId={gh_id}")["data"]
        return next((d["state"] for d in data if d["sn"] == sn), None)

    try:
        # 1) 复位执行器：风机停 → 湿帘关（互锁要求先停风机才能关湿帘）
        http_json(host, port, "POST", "/api/control",
                  {"deviceSn": "FAN-001", "action": "OFF", "operator": OPERATOR})
        wait_for("风机 FAN-001 停止", lambda: device_state("FAN-001") == "OFF", timeout)
        http_json(host, port, "POST", "/api/control",
                  {"deviceSn": "WC-001", "action": "CLOSE", "operator": OPERATOR})
        wait_for("湿帘 WC-001 关闭", lambda: device_state("WC-001") == "CLOSED", timeout)

        # 2) 湿帘关闭状态下强启风机 → 安全互锁拦截 → WARN 告警 → alarm 事件
        resp = http_json(host, port, "POST", "/api/control",
                         {"deviceSn": "FAN-001", "action": "ON", "operator": OPERATOR})
        if resp.get("code") == 0:
            print("[自检] 警告：风机启动未被互锁拦截（湿帘可能未关到位），alarm 事件未必产生", flush=True)
        else:
            print(f"[自检] 互锁已拦截：{resp.get('message')}", flush=True)
        wait_for("alarm 事件", lambda: ws.snapshot()[0]["alarm"] > 0, timeout)
    finally:
        http_json(host, port, "PUT", f"/api/greenhouses/{gh_id}/mode?mode={orig_mode}&operator={OPERATOR}")
        print(f"[自检] 已恢复原模式 {orig_mode}", flush=True)

    # 3) 汇总：再等一个上报周期，尽量多捕获 sensor
    time.sleep(3)
    counts, last_alarm = ws.snapshot()
    ws.close()

    got = sum(1 for t in EVENT_TYPES if counts[t] > 0)
    print(f"\n[自检结果] 捕获事件类型 {got}/{len(EVENT_TYPES)}")
    hints = {
        "sensor": "缺 sensor：设备模拟器未运行？（python3 simulator/simulator.py）",
        "device": "缺 device：网关离线，指令无回执？",
        "alarm": "缺 alarm：互锁拦截未触发，查看后端日志",
        "command": "缺 command：指令未下发，查看后端日志",
    }
    for t in EVENT_TYPES:
        mark = "✓" if counts[t] > 0 else "✗"
        extra = f" 最近：{last_alarm}" if t == "alarm" and last_alarm else ""
        print(f"  {mark} {t:<8} × {counts[t]}{extra}")
        if counts[t] == 0:
            print(f"    {hints[t]}")
    sys.exit(0 if got == len(EVENT_TYPES) else 1)


if __name__ == "__main__":
    main()
