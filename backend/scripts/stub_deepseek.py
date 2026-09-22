"""零付费的 DeepSeek **回显桩**：把每条请求收到的 `model` 记下来，供"两档模型（C-56）"取证。

为什么需要它：`AnswerModelTierTest` 只能证明"配置怎么解析"，**证明不了"CLI 真的把强档传给了作答器"**。
真跑一次付费冒烟能证明，但要花钱；而"C 端到底发了哪个模型名"这件事，**让服务端回显就够了**——
桩在这里扮演服务端。

用法（在 `backend/` 下）：

    python scripts\\stub_deepseek.py --port 8899 --log target\\stub\\calls.txt
    # 另一个窗口（进程环境变量覆盖 .env，见 LlmRerankerFactory#environmentWithDotEnv 的注释）：
    $env:DEEPSEEK_BASE_URL = 'http://127.0.0.1:8899/v1'
    java -cp "target\\classes;<cp.txt 的内容>" com.canalmind.eval.pipeline.LayerRunCli --smoke

日志每行一条：`model=... json_mode=... msgs=...`，**只记模型名与请求形状，不记任何密钥**。
"""
from __future__ import annotations

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG_LOCK = threading.Lock()

# 路由/抽取要的是 JSON；给一个"键够多"的通用对象，避免桩把链路直接卡死在解析失败上
GENERIC_JSON = (
    '{"intent":"rag","query_type":"rag","type":"rag","channel":"retrieval",'
    '"need_tools":false,"tools":[],"arguments":{},"answer":"(stub)"}'
)
PLAIN_ANSWER = "（本地桩作答）这是一条零付费验证用的固定答复，用于确认强档模型真的被调用。"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):  # noqa: N802 —— BaseHTTPRequestHandler 的命名约定
        raw = self._read_body()
        try:
            body = json.loads(raw.decode("utf-8", "replace") or "{}")
        except (json.JSONDecodeError, UnicodeDecodeError):
            body = {}

        model = str(body.get("model", "?"))
        json_mode = str((body.get("response_format") or {}).get("type", "-"))
        messages = body.get("messages") or []
        head = ""
        if messages:
            head = str(messages[0].get("content", ""))[:40].replace("\n", " ")
        # ⚠️ 第一版只记 model/json_mode，结果**读体失败时也照样工作**（4 条全是 `model=? msgs=0`），
        #    差点把"桩坏了"当成"模型名没传" ⇒ 现在把请求头与体长一起记下来，坏了一看就知道。
        self._log(f"{self.command} {self.path} len={len(raw)} "
                  f"ct={self.headers.get('Content-Type')} "
                  f"te={self.headers.get('Transfer-Encoding')} "
                  f"ce={self.headers.get('Content-Encoding')} "
                  f"model={model} json_mode={json_mode} msgs={len(messages)} head={head}")

        content = GENERIC_JSON if json_mode == "json_object" else PLAIN_ANSWER
        payload = json.dumps({
            "id": "stub-chatcmpl",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": model,          # ⚠️ 原样回显：这正是"真的用了这个模型"的证据
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": content}}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        }, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _read_body(self) -> bytes:
        """把请求体完整读出来：**必须处理分块传输**。

        ⚠️ 实测踩到：Spring AI 这条链路的请求体是 `Transfer-Encoding: chunked`（**没有 Content-Length**），
        而 `BaseHTTPRequestHandler` **不会**自动解分块 ⇒ 只按 Content-Length 读会读到**空体**，
        于是桩"照常返回 200、链路照常绿"，只有日志里露出 `model=?`。
        """
        raw = b""
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            while True:
                line = self.rfile.readline().strip()
                if not line:
                    continue
                size = int(line.split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()          # 吃掉结尾 CRLF
                    break
                raw += self.rfile.read(size)
                self.rfile.readline()              # 吃掉该块后的 CRLF
        else:
            raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        if "gzip" in (self.headers.get("Content-Encoding") or "").lower():
            import gzip
            raw = gzip.decompress(raw)
        return raw

    def _log(self, line: str) -> None:
        with LOG_LOCK:
            with open(self.server.log_path, "a", encoding="utf-8", newline="\n") as fh:
                fh.write(line + "\n")
        print(line, flush=True)

    def log_message(self, fmt, *args):  # 静音默认的每请求日志（我们自己记）
        return


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8899)
    ap.add_argument("--log", default="target/stub/calls.txt")
    args = ap.parse_args()

    import os
    os.makedirs(os.path.dirname(args.log) or ".", exist_ok=True)
    with open(args.log, "w", encoding="utf-8", newline="\n"):
        pass  # 每次启动清空

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.log_path = args.log
    print(f"stub listening on http://127.0.0.1:{args.port}/v1  log={args.log}", flush=True)
    server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
