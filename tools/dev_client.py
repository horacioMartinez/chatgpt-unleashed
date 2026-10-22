#!/usr/bin/env python3
"""Call the debug-only unleashed local transport; never invokes device commands."""
import argparse
import json
import socket
import struct
import uuid


def call(port, rpc, timeout=45):
    request = {"requestId": str(uuid.uuid4()), "jsonrpc": rpc}
    payload = json.dumps(request).encode()
    def receive(connection, length):
        chunks = bytearray()
        while len(chunks) < length:
            part = connection.recv(length - len(chunks))
            if not part:
                raise ConnectionError("App closed the connection before replying")
            chunks.extend(part)
        return bytes(chunks)
    with socket.create_connection(("127.0.0.1", port), timeout=5) as connection:
        connection.settimeout(timeout + 10)
        connection.sendall(struct.pack("!I", len(payload)) + payload)
        size = struct.unpack("!I", receive(connection, 4))[0]
        if size > 10_000_000:
            raise ValueError("Unexpectedly large app response")
        reply = json.loads(receive(connection, size))
    if reply.get("requestId") != request["requestId"]:
        raise ValueError("App response request ID mismatch")
    return reply

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Call the debug-only unleashed MCP socket.")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--rpc", required=True, help="JSON-RPC request")
    args = parser.parse_args()
    print(json.dumps(call(args.port, rpc=json.loads(args.rpc)), ensure_ascii=False, indent=2))
