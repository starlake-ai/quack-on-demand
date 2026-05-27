#!/usr/bin/env bash
# Minimal fake Quack: serves POST / with a static JSON reply.
# Used so the smoke test can run without the real Quack binary.
PORT="${1:-8080}"
TOKEN="${2:-no-token}"
python3 -c "
import sys, json
from http.server import BaseHTTPRequestHandler, HTTPServer

class H(BaseHTTPRequestHandler):
    def do_POST(self):
        auth = self.headers.get('Authorization', '')
        if 'Bearer ${TOKEN}' not in auth:
            self.send_response(401); self.end_headers(); return
        body = b'{\"columns\":[\"ok\"],\"rows\":[[1]]}'
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers(); self.wfile.write(body)
    def log_message(self, *args): pass

HTTPServer(('127.0.0.1', ${PORT}), H).serve_forever()
"