#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
保养提醒推送链路自检：验证移动端通知所依赖的服务端通道。

检查项：
  1. GET  /api/maintenance/reminders   提醒接口可用（移动端补拉入口）；
  2. GET  /api/maintenance/fault-stats 故障统计接口可用（回执时间口径）；
  3. WS   /ws/realtime                 触发 run-now 后能收到 maintenance 帧
     （移动端收到该帧后会重新拉取提醒并触发系统通知/应用内提醒）。

前置条件：后端已启动（:8080）。run-now 是幂等重算，不会改变业务数据。
用法：python3 simulator/maintenance_notify_check.py [--host 127.0.0.1] [--port 8080] [--timeout 10]
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


class WsClient:
    """最小 WebSocket 客户端（标准库实现）：握手 + 文本帧接收 + ping/pong。"""

    def __init__(self, host, port, path="/ws/realtime"):
        self.host, self.port, self.path = host, port, path
        self.sock = None
        self.running = False
        self.thread = None
        self.lock = threading.Lock()
        self.frames = []

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
                if opcode == 0x1:
                    self._on_text(payload.decode("utf-8", "replace"))
                elif opcode == 0x9:
                    self._send_frame(0xA, payload)
                elif opcode == 0x8:
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
        with self.lock:
            self.frames.append(msg)

    def wait_event(self, event, timeout):
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self.lock:
                hit = [f for f in self.frames if f.get("event") == event]
            if hit:
                return hit[-1]
            time.sleep(0.2)
        return None

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


def main():
    ap = argparse.ArgumentParser(description="保养提醒推送链路自检")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--timeout", type=int, default=10)
    args = ap.parse_args()
    host, port, timeout = args.host, args.port, args.timeout
    failures = []

    # 1) 提醒接口（移动端补拉入口）
    try:
        resp = http_json(host, port, "GET", "/api/maintenance/reminders")
        n = len(resp.get("data") or [])
        print(f"[自检] reminders 接口正常，当前待处理提醒 {n} 条", flush=True)
    except Exception as e:
        failures.append(f"reminders 接口异常：{e}")

    # 2) 故障统计接口（回执时间口径）
    try:
        resp = http_json(host, port, "GET", "/api/maintenance/fault-stats?days=30")
        data = resp.get("data") or {}
        print(f"[自检] fault-stats 接口正常，近 {data.get('days')} 天故障 {data.get('totalFaults')} 次", flush=True)
    except Exception as e:
        failures.append(f"fault-stats 接口异常：{e}")

    # 3) WS maintenance 帧（移动端实时刷新/通知触发源）
    try:
        ws = WsClient(host, port)
        ws.connect()
        print("[自检] 已订阅 /ws/realtime，触发 run-now 幂等重算…", flush=True)
        http_json(host, port, "POST", "/api/maintenance/run-now")
        frame = ws.wait_event("maintenance", timeout)
        ws.close()
        if frame:
            print(f"[自检] 收到 maintenance 帧：{json.dumps(frame.get('data'), ensure_ascii=False)}", flush=True)
        else:
            failures.append(f"{timeout}s 内未收到 maintenance WS 帧")
    except Exception as e:
        failures.append(f"WS 检查异常：{e}")

    if failures:
        print("\n[自检结果] 未通过：", flush=True)
        for f in failures:
            print(f"  ✗ {f}", flush=True)
        sys.exit(1)
    print("\n[自检结果] 3/3 通过：提醒接口、故障统计接口、maintenance WS 帧均正常", flush=True)
    sys.exit(0)


if __name__ == "__main__":
    main()
