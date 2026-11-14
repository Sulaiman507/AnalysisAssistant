#!/usr/bin/env python3
"""Mock MT MCP server — TEST DOUBLE ONLY for Phase 1 client verification.

Listens on 127.0.0.1:8787 and answers initialize / tools/list / tools/call
with CLEARLY FAKE tool names (mock.*). Never confuse this with the real MT list.

Usage:
  python3 tools/mock_mcp_server.py [--mode json|sse] [--port 8787]
"""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

FAKE_TOOLS = [
    {"name": "mock.ping", "description": "MOCK TOOL — test double only",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "mock.list_apks", "description": "MOCK TOOL — test double only",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "mock.open_apk", "description": "MOCK TOOL — test double only",
     "inputSchema": {"type": "object", "properties": {"apkPath": {"type": "string"}}}},
    {"name": "mock.dex_outline", "description": "MOCK TOOL — test double only",
     "inputSchema": {"type": "object", "properties": {"className": {"type": "string"}}}},
]


def make_response(req_id, result):
    return {"jsonrpc": "2.0", "id": req_id, "result": result}


class Handler(BaseHTTPRequestHandler):
    mode = "json"

    def log_message(self, *a):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8")
        try:
            req = json.loads(body)
        except Exception:
            self.send_error(400, "bad json")
            return
        method = req.get("method")
        req_id = req.get("id")
        if method == "initialize":
            result = {"protocolVersion": "2025-03-26", "capabilities": {},
                      "serverInfo": {"name": "mock-mt-mcp", "version": "0.0.0-mock"}}
        elif method == "tools/list":
            result = {"tools": FAKE_TOOLS}
        elif method == "tools/call":
            name = (req.get("params") or {}).get("name")
            result = {"content": [{"type": "text", "text": f"MOCK echo for tool {name}"}],
                      "isError": False}
        elif method == "notifications/initialized":
            self.send_response(202)
            self.end_headers()
            return
        else:
            payload = {"jsonrpc": "2.0", "id": req_id,
                       "error": {"code": -32601, "message": f"Method not found: {method}"}}
            self._send(payload, 200)
            return
        self._send(make_response(req_id, result), 200)

    def _send(self, payload, status):
        data = json.dumps(payload)
        if self.mode == "sse":
            body = ("event: message\n" + "data: " + data + "\n\n").encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            body = data.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["json", "sse"], default="json")
    ap.add_argument("--port", type=int, default=8787)
    args = ap.parse_args()
    Handler.mode = args.mode
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Mock MCP server on 127.0.0.1:{args.port} (mode={args.mode}) — FAKE tools only")
    server.serve_forever()
