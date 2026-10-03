#!/usr/bin/env python3
"""A loopback stand-in for an ntfy server, for the alert tests.

  ntfy-stub.py PORT LOGFILE

Accepts the relay's JSON publish (POST /) and appends each body to LOGFILE as one line of JSON, with the request's path and
the time it arrived and whether it carried an Authorization header, then answers 200. Nothing is forwarded anywhere; it listens on 127.0.0.1 only.
The test script reads LOGFILE to see exactly what a server (and anyone who can read its log) would have been given, and fires
each message's `click` link at the app the way the ntfy app does when the notification is tapped.
"""
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT, LOG = int(sys.argv[1]), sys.argv[2]


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8", "replace")
        with open(LOG, "a") as f:
            f.write(json.dumps({"t": time.time(), "path": self.path, "auth": "Authorization" in self.headers, "body": body}) + "\n")
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"{}")

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
