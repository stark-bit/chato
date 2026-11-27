import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TRANSCRIPT = "check one two"
events = []
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def body(self):
        return self.rfile.read(int(self.headers.get("Content-Length", 0)))

    def reply(self, code, text, kind="text/plain"):
        data = text.encode()
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path != "/log":
            return self.reply(404, "")
        with lock:
            self.reply(200, json.dumps(events), "application/json")

    def do_POST(self):
        data = self.body()
        if self.path == "/reset":
            with lock:
                events.clear()
            return self.reply(200, "")
        with lock:
            events.append({"path": self.path, "bytes": len(data),
                           "auth": self.headers.get("Authorization", ""),
                           "body": "" if self.path.startswith("/v1/") else data.decode(errors="replace")})
        if self.path == "/v1/audio/transcriptions":
            return self.reply(200, TRANSCRIPT)
        if self.path.startswith("/endpoint/"):
            return self.reply(200, "{}", "application/json")
        self.reply(404, "")


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
