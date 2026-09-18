#!/usr/bin/env python3
"""Dependency-free authenticated smoke client for replay-mcp.bridge/1."""

import argparse
import base64
import hashlib
import json
import os
import socket
import struct
import time
import uuid
from pathlib import Path


class Bridge:
    def __init__(self, descriptor, token):
        self.descriptor = descriptor
        self.socket = socket.create_connection(("127.0.0.1", descriptor["port"]), timeout=10)
        key = base64.b64encode(os.urandom(16)).decode()
        request = (
            "GET /bridge HTTP/1.1\r\n"
            f"Host: 127.0.0.1:{descriptor['port']}\r\n"
            "Upgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
            f"X-Replay-MCP-Token: {token}\r\n\r\n"
        )
        self.socket.sendall(request.encode("ascii"))
        response = self._read_headers()
        if not response.startswith("HTTP/1.1 101"):
            raise RuntimeError(f"upgrade rejected: {response.splitlines()[0]}")
        expected = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        headers = {line.split(":", 1)[0].lower(): line.split(":", 1)[1].strip() for line in response.splitlines()[1:] if ":" in line}
        if headers.get("sec-websocket-accept") != expected:
            raise RuntimeError("invalid WebSocket accept hash")
        self.counter = 0

    def _read_headers(self):
        data = bytearray()
        while b"\r\n\r\n" not in data:
            data.extend(self.socket.recv(4096))
            if len(data) > 65536:
                raise RuntimeError("oversized HTTP response")
        return data.decode("iso-8859-1")

    def rpc(self, method, params=None):
        self.counter += 1
        request_id = str(self.counter)
        payload = json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params or {}}).encode()
        self._send_frame(payload)
        while True:
            response = json.loads(self._read_frame())
            if response.get("id") == request_id:
                return response

    def hello(self):
        response = self.rpc("system.hello", {"protocol": "replay-mcp.bridge/1", "instance_id": self.descriptor["instanceId"]})
        if response.get("result", {}).get("process_id") != self.descriptor["processId"]:
            raise RuntimeError("authenticated process identity mismatch")

    def _send_frame(self, payload):
        mask = os.urandom(4)
        length = len(payload)
        header = bytearray([0x81])
        if length < 126:
            header.append(0x80 | length)
        elif length <= 65535:
            header.append(0x80 | 126); header.extend(struct.pack("!H", length))
        else:
            header.append(0x80 | 127); header.extend(struct.pack("!Q", length))
        header.extend(mask)
        header.extend(byte ^ mask[index % 4] for index, byte in enumerate(payload))
        self.socket.sendall(header)

    def _read_exact(self, length):
        data = bytearray()
        while len(data) < length:
            part = self.socket.recv(length - len(data))
            if not part:
                raise EOFError("bridge disconnected")
            data.extend(part)
        return bytes(data)

    def _read_frame(self):
        first, second = self._read_exact(2)
        opcode, length = first & 0x0F, second & 0x7F
        if length == 126:
            length = struct.unpack("!H", self._read_exact(2))[0]
        elif length == 127:
            length = struct.unpack("!Q", self._read_exact(8))[0]
        if second & 0x80:
            mask = self._read_exact(4)
        else:
            mask = None
        payload = self._read_exact(length)
        if mask:
            payload = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
        if opcode == 8:
            raise EOFError("bridge closed WebSocket")
        if opcode == 9:
            self._send_frame(payload)
            return self._read_frame()
        if opcode != 1:
            return self._read_frame()
        return payload.decode("utf-8")

    def close(self):
        self.socket.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("game_dir", type=Path)
    parser.add_argument("--instance")
    parser.add_argument("--replay-smoke", action="store_true", help="also open a replay and render an exact-time still")
    args = parser.parse_args()
    roots = sorted((args.game_dir / ".replay-mcp" / "instances").glob("*/bridge.json"), key=lambda path: path.stat().st_mtime, reverse=True)
    live_roots = []
    for path in roots:
        try:
            process_id = json.loads(path.read_text())["processId"]
            os.kill(process_id, 0)
            live_roots.append(path)
        except (KeyError, ValueError, ProcessLookupError, PermissionError):
            continue
    roots = live_roots
    if args.instance:
        roots = [path for path in roots if path.parent.name == args.instance]
    if len(roots) != 1:
        raise SystemExit(f"expected one matching bridge, found {len(roots)}")
    descriptor_path = roots[0]
    descriptor = json.loads(descriptor_path.read_text())
    token = descriptor_path.with_name("token").read_text().strip()
    first, second = Bridge(descriptor, token), Bridge(descriptor, token)
    try:
        first.hello(); second.hello()
        assert "result" in first.rpc("system.status")
        assert "result" in second.rpc("observation.snapshot", {"deadline_ms": 30000})
        for view in ("player", "clean", "annotated"):
            frame = first.rpc("observation.framebuffer", {"view": view, "deadline_ms": 30000})["result"]
            artifact = Path(frame["path"])
            assert artifact.is_file() and hashlib.sha256(artifact.read_bytes()).hexdigest() == frame["sha256"]
        burst = first.rpc("observation.motion_burst", {"view": "clean", "frames": 2, "deadline_ms": 30000})["result"]
        assert len(burst["frames"]) + burst["dropped_frames"] == 2
        acquired = first.rpc("lease.acquire", {"owner_label": "bridge-smoke", "deadline_ms": 30000})["result"]
        busy = second.rpc("lease.acquire", {"owner_label": "second", "deadline_ms": 30000})
        assert busy["error"]["code"] == "control_busy"
        cancelled = first.rpc("system.cancel", {"lease_id": acquired["lease_id"], "fence": acquired["fence"], "request_id": "smoke-cancel", "operation_id": "missing", "deadline_ms": 30000})
        assert cancelled["result"]["cancelled"] is False
        first.rpc("lease.release", {"lease_id": acquired["lease_id"], "fence": acquired["fence"], "deadline_ms": 30000})
        if args.replay_smoke:
            replay = first.rpc("replay.list", {"deadline_ms": 30000})["result"]
            if not replay:
                raise RuntimeError("replay smoke requested but Replay Mod library is empty")
            source = Path(replay[0]["path"])
            source_digest = hashlib.sha256(source.read_bytes()).hexdigest()
            acquired = first.rpc("lease.acquire", {"owner_label": "replay-smoke", "deadline_ms": 30000})["result"]
            credentials = {"lease_id": acquired["lease_id"], "fence": acquired["fence"]}
            opened = first.rpc("replay.open", {**credentials, "request_id": f"open-{uuid.uuid4()}", "path": replay[0]["name"], "deadline_ms": 30000})
            if "error" in opened:
                raise RuntimeError(f"replay open failed: {opened['error']}")
            ready_deadline = time.monotonic() + 30
            while time.monotonic() < ready_deadline:
                status = first.rpc("system.status", {"deadline_ms": 30000})["result"]
                if status.get("replay_ready"):
                    break
                time.sleep(.1)
            else:
                raise RuntimeError("Replay Mod did not finish loading the replay camera")
            if not first.rpc("lease.status")["result"]["held"]:
                acquired = first.rpc("lease.acquire", {"owner_label": "replay-smoke-render", "deadline_ms": 30000})["result"]
                credentials = {"lease_id": acquired["lease_id"], "fence": acquired["fence"]}
            output_name = f"replay-mcp-smoke-{uuid.uuid4()}.png"
            still = first.rpc("render.still", {**credentials, "request_id": f"still-{uuid.uuid4()}", "output": output_name,
                                               "time_us": 0, "width": 320, "height": 180, "anti_aliasing": 1,
                                               "deadline_ms": 30000})
            if "error" in still:
                raise RuntimeError(f"still render failed: {still['error']}")
            output = args.game_dir / "replay_videos" / output_name
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline and not output.is_file():
                time.sleep(.1)
            if not output.is_file() or output.stat().st_size == 0:
                raise RuntimeError("exact-time still render did not produce a PNG")
            if output.read_bytes()[:8] != b"\x89PNG\r\n\x1a\n":
                raise RuntimeError("exact-time still output is not a PNG")
            if not first.rpc("lease.status")["result"]["held"]:
                acquired = first.rpc("lease.acquire", {"owner_label": "replay-smoke-close", "deadline_ms": 30000})["result"]
                credentials = {"lease_id": acquired["lease_id"], "fence": acquired["fence"]}
            closed = first.rpc("replay.close", {**credentials, "request_id": f"close-{uuid.uuid4()}", "deadline_ms": 30000})
            if "error" in closed:
                raise RuntimeError(f"replay close failed: {closed['error']}")
            if hashlib.sha256(source.read_bytes()).hexdigest() != source_digest:
                raise RuntimeError("source replay changed during working-copy smoke test")
            first.rpc("lease.release", {**credentials, "deadline_ms": 30000})
    finally:
        first.close(); second.close()
    reconnect = Bridge(descriptor, token)
    try:
        reconnect.hello(); assert reconnect.rpc("lease.status")["result"]["held"] is False
    finally:
        reconnect.close()
    print("Replay MCP bridge smoke test passed")


if __name__ == "__main__":
    main()
