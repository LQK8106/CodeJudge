#!/usr/bin/env python3
"""本地告警接收端（webhook sink）—— 只做一件事：把收到的告警打印到 stdout。

用途：证明「Alertmanager → HTTP 接收端」这一段真的通了。
  · 没有它，验证告警只能看 Alertmanager UI —— 那只证明「告警存在」，不证明「告警发出去了」；
  · 有了它，`docker logs codejudge-alert-sink` 就是接收端的收件记录（含原始 JSON）。

生产不需要它：把 ALERTMANAGER_WEBHOOK_URL 指向真实接收端（转发器 / 告警平台）即可。
"""

import json
from http.server import BaseHTTPRequestHandler, HTTPServer


class Sink(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802 - 标准库命名
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8", "replace")
        try:
            payload = json.loads(raw)
            brief = [
                (
                    a.get("labels", {}).get("alertname"),
                    a.get("status"),
                    a.get("labels", {}).get("severity"),
                )
                for a in payload.get("alerts") or []
            ]
        except Exception:
            brief = []
        print(f"[alert-sink] POST {self.path} bytes={length} alerts={brief}", flush=True)
        print(f"[alert-sink] body={raw}", flush=True)

        body = b'{"ok":true}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):  # 静默默认访问日志，避免和目标日志混在一起
        pass


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8080), Sink).serve_forever()
