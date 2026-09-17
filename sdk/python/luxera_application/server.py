"""LapServer —— 一个能被 LAP 平台调用的远端应用的 HTTP 骨架。

设计上它像一个极小的路由器, 而不是一个框架: 平台对远端的全部要求是
"在 ``runtime.remote.baseUrl`` 上收 POST, 回 LAP 形状的 JSON", 任何框架
(Flask/FastAPI/甚至另一个 Java 服务)都能做到。这个类的价值在于把
"哪些字段必须验、哪些字段随便用、错误该怎么回"这几个协议判断写对一次。

与框架的取舍: 用标准库 ``http.server`` 而不是 Flask —— SDK 不给远端应用
引入任何第三方依赖, "协议优先、SDK 非必须"这句话才立得住(方案 §75/§104:
连不用本 SDK 的实现者也能照着协议文档对接)。
"""

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable
from urllib.parse import parse_qs, unquote, urlparse

from .signature import (
    HEADER_SIGNATURE,
    HEADER_TIMESTAMP,
    secret_from_env,
    verify_request,
)


class LapError(Exception):
    """应用侧主动说的"不" —— 回给平台的形状与 LAP 的 ActionStatus 对齐。

    ``status`` 用平台认识的词(见 ``STATUS_BY_CODE`` 的键集), 回 4xx/5xx 之前
    先想想这里是不是更合适: 平台把 4xx 映射成 REMOTE_* 错误码, 而这里的
    code/message 会原样出现在 ActionResponse.error 里, 用户看到的是后者。
    """

    def __init__(self, code: str, message: str, status: str = "FAILED"):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status


# SDK 认识的状态词 —— 与 contracts 的 ActionStatus 同一张表。不认识的
# 一律按 FAILED 回, 而不是让一个拼错的词静默变成"成功"。
STATUS_BY_CODE = {
    "DENIED": "DENIED",
    "NOT_FOUND": "NOT_FOUND",
    "STATE_CONFLICT": "STATE_CONFLICT",
    "INVALID_ARGUMENT": "INVALID_ARGUMENT",
    "FAILED": "FAILED",
}


class LapRequest:
    """平台一次转发过来的全部材料。

    ``action`` / ``input`` / ``target`` / ``principal`` / ``idempotency_key``
    —— 这五个就是协议的全部。``raw`` 留给应用自己做协议之外的扩展
    (metadata、自定制的 trace 头之类), SDK 不解释它。
    """

    def __init__(self, payload: dict, idempotency_key: str | None, raw_body: str):
        self.action = payload.get("action", "")
        self.input = payload.get("input") or {}
        self.target = payload.get("target")
        self.principal = payload.get("principal") or {}
        self.correlation_id = payload.get("correlationId")
        self.expected_version = payload.get("expectedResourceVersion")
        self.idempotency_key = idempotency_key
        self.raw = payload
        self.raw_body = raw_body


class IdempotencyStore:
    """已答问题的记忆 —— 幂等三行代码, 但写错的后果是重复落子。

    平台转发的键是"这一次逻辑调用"的确定函数, 重试(网络超时后重发、平台
    抢占重放)会得到同一个键。远端要做的只是: 第一次记住结果, 之后同键直接回。
    ``None`` 表示"没答过" —— 所以存结果时要连"结果是失败"也一起存,
    否则失败的那次会被重试成第二次执行。
    """

    def __init__(self, capacity: int = 1024):
        self._entries: dict[str, dict] = {}
        self._order: list[str] = []
        self._capacity = capacity
        self._lock = threading.Lock()

    def get(self, key: str | None) -> dict | None:
        if not key:
            return None
        with self._lock:
            return self._entries.get(key)

    def put(self, key: str | None, response: dict):
        if not key:
            return
        with self._lock:
            if key not in self._entries:
                self._order.append(key)
            self._entries[key] = response
            while len(self._order) > self._capacity:
                oldest = self._order.pop(0)
                self._entries.pop(oldest, None)


class PageRequest:
    """一次 GET 请求 —— 给应用**自己的界面**用的那一半。

    <h2>它与 LapRequest 为什么是两个类</h2>

    两者回答的问题不一样, 而把它们合成一个会立刻带来一个错: `LapRequest.principal` 是
    **平台签过名**的身份, 而一个 GET 请求上的任何东西都是浏览器自己说的。同一个类里同时
    装着"可信的身份"与"不可信的输入", 早晚有人把后者当前者用。

    所以这一个没有 `principal`。要认人, 应用得走它自己的那套(自己的 cookie / 自己的
    会话)—— 那也是这一层该有分工: 平台负责"应用能不能被打开", 应用负责"打开的人是谁"。
    """

    def __init__(self, path: str, params: dict, query: dict):
        self.path = path
        self.params = params
        self.query = query


class LapServer:
    """按 action id 分发的极小路由器 + 验签 + 幂等, 见模块注释。"""

    def __init__(self, secret: str | None = None, *, secret_env: str = "LAP_SERVICE_SECRET",
                 replay_window_seconds: int | None = None):
        self.secret = secret if secret is not None else secret_from_env(secret_env)
        self.replay_window_seconds = replay_window_seconds
        self._handlers: dict[str, Callable[[LapRequest], dict]] = {}
        self._pages: list[tuple[list[str], Callable[[PageRequest], object]]] = []
        self._idempotency = IdempotencyStore()
        self._seen_nonces: dict[str, float] = {}

    # ─────────────────────────── 注册 ───────────────────────────

    def action(self, action_id: str):
        """装饰器: ``@server.action("game.make_move")`` —— id 与 manifest 里的一致。"""
        def register(func: Callable[[LapRequest], dict]):
            self._handlers[action_id] = func
            return func
        return register

    def page(self, path: str):
        """装饰器: ``@server.page("/ui/{sessionId}")`` —— 应用自己那一页。

        <h2>为什么这一步属于 SDK</h2>

        一份 manifest 里的 `ui.entry` 是**平台会让浏览器直接打开**的地址, 而那个地址必须由
        应用自己提供 —— 平台不托管第三方界面(§17/§69)。在这之前, 用本 SDK 写出来的远端
        应用只好再起一个静态服务器(或者干脆让 entry 指向一个 404), 只为了让一个已经声明过的
        地址真的存在。让同一台服务顺手把它端出来, 应用才可能只有一个进程。

        <h2>它不验签, 也不该验</h2>

        打开这一页的是**用户的浏览器**, 它没有那把 HMAC 密钥、也不可能拿着它。所以这一半
        路由与 `/lap/actions:execute` 的信任模型完全不同: 那边的每一个字段都是平台签过的,
        这边的每一个字段都是别人可以随便写的。这里**不做**任何鉴权 —— 认出"谁在打开"是
        应用自己的事, 而 SDK 能做的是**不给**这一半任何 `principal`, 免得有人误以为有。

        <h2>返回值</h2>

        返回 ``str`` 当 HTML、``dict``/``list`` 当 JSON、``(状态码, content_type, body)``
        自己全管; 抛 :class:`LapError` 就当这一次请求失败。路径里的 ``{name}`` 会按段捕获,
        解出来的值进 ``PageRequest.params``。
        """
        def register(func: Callable[[PageRequest], object]):
            segments = [s for s in path.split("/") if s != ""]
            self._pages.append((segments, func))
            return func
        return register

    # ─────────────────────────── 执行 ───────────────────────────

    def dispatch(self, request: LapRequest) -> tuple[int, dict]:
        """跑一次动作, 返回 (http_status, body)。测试直接调它, 不必起端口。"""
        if self.secret is None:
            # 没配密钥 = 这个远端谁都能冒充平台调它。宁可拒绝一切, 不裸奔。
            return 503, self._error("REMOTE_NOT_CONFIGURED", "服务端未配置 LAP_SERVICE_SECRET")
        if request.action not in self._handlers:
            return 404, self._error("UNKNOWN_ACTION",
                                    f"应用不认识动作 {request.action!r} —— manifest 与实现哪个改了?")
        cached = self._idempotency.get(request.idempotency_key)
        if cached is not None:
            # 幂等回放: 同键同答, 连失败也重放(见 IdempotencyStore 的类注释)。
            return 200, cached
        try:
            result = self._handlers[request.action](request)
            body = {"result": result} if result is not None else {"result": {}}
        except LapError as e:
            status = STATUS_BY_CODE.get(e.status, "FAILED")
            return self._status_for(status), self._error(e.code, e.message, status)
        self._idempotency.put(request.idempotency_key, body)
        return 200, body

    def verify(self, timestamp: str | None, body: str, signature: str | None) -> bool:
        if self.replay_window_seconds is not None:
            return verify_request(self.secret, timestamp, body, signature,
                                  replay_window_seconds=self.replay_window_seconds)
        return verify_request(self.secret, timestamp, body, signature)

    # ─────────────────────────── HTTP ───────────────────────────

    def handler(self) -> type:
        server = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802 — BaseHTTPRequestHandler 的命名
                parsed = urlparse(self.path)
                match = server._match_page(parsed.path)
                if match is None:
                    # 浏览器打开一个不存在的路径 —— 回 JSON 是给机器看的, 但这一半的读者
                    # 是人。一句人能读的话 + 正确的状态码, 比一个协议形状的错误体有用。
                    self._reply_raw(404, "text/plain; charset=utf-8",
                                    f"没有这一页: {parsed.path}".encode("utf-8"))
                    return
                func, params = match
                query = {k: v[0] for k, v in parse_qs(parsed.query).items()}
                try:
                    result = func(PageRequest(parsed.path, params, query))
                except LapError as e:
                    self._reply_raw(500, "text/plain; charset=utf-8",
                                    f"{e.code}: {e.message}".encode("utf-8"))
                    return
                status, content_type, body = server._normalize_page(result)
                self._reply_raw(status, content_type, body)

            def do_POST(self):  # noqa: N802 — BaseHTTPRequestHandler 的命名
                length = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(length).decode("utf-8") if length else ""
                if not server.verify(self.headers.get(HEADER_TIMESTAMP), raw,
                                     self.headers.get(HEADER_SIGNATURE)):
                    self._reply(401, {"error": {"code": "BAD_SIGNATURE",
                                                "message": "签名校验失败(伪造/重放/时钟错位)"}})
                    return
                try:
                    payload = json.loads(raw) if raw else {}
                except json.JSONDecodeError:
                    self._reply(400, {"error": {"code": "BAD_REQUEST", "message": "body 不是 JSON"}})
                    return
                request = LapRequest(payload, self.headers.get("Idempotency-Key"), raw)
                status, body = server.dispatch(request)
                self._reply(status, body)

            def _reply(self, status: int, body: dict):
                data = json.dumps(body, ensure_ascii=False).encode("utf-8")
                self._reply_raw(status, "application/json; charset=utf-8", data)

            def _reply_raw(self, status: int, content_type: str, data: bytes):
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(data)))
                # **刻意不发 `X-Frame-Options`**: 这一页的正当用法就是被平台放进 iframe
                # (它就是 `ui.entry`)。发一个 SAMEORIGIN 会让平台那一侧的 iframe 整个变白,
                # 而症状是"应用打开是空白的" —— 与这一行隔了十万八千里, 极难归因。
                # 想限制谁能嵌的应用该用 `Content-Security-Policy: frame-ancestors` 点名
                # 平台的域名 —— 那是应用自己的决定, SDK 不替它做。
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, fmt, *args):  # 静音: 平台 3 秒超时, 日志不该比业务还吵
                pass

        return Handler

    def serve(self, host: str = "127.0.0.1", port: int = 8095):
        """阻塞式起服务 —— CLI/验收脚本用; 嵌进别的框架时用 :meth:`dispatch`。"""
        http = ThreadingHTTPServer((host, port), self.handler())
        print(f"[LapServer] listening on {host}:{port}", flush=True)
        try:
            http.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            http.server_close()

    # ─────────────────────────── 内部 ───────────────────────────

    def _match_page(self, path: str):
        """按段匹配一条 GET 路径。``{name}`` 捕获一段, 别的段必须全等。

        先注册的先生效 —— 与大多数路由器一致, 也让"具体路径写在通配路径前面"这条
        人人都懂的经验在这里成立。
        """
        parts = [unquote(s) for s in path.split("/") if s != ""]
        for segments, func in self._pages:
            if len(segments) != len(parts):
                continue
            params = {}
            for declared, actual in zip(segments, parts):
                if declared.startswith("{") and declared.endswith("}"):
                    params[declared[1:-1]] = actual
                elif declared != actual:
                    break
            else:
                return func, params
        return None

    @staticmethod
    def _normalize_page(result) -> tuple[int, str, bytes]:
        """把处理函数的返回值收成 (状态码, content-type, 字节)。见 :meth:`page`。"""
        if isinstance(result, tuple):
            status, content_type, body = result
            if isinstance(body, bytes):
                return status, content_type, body
            return status, content_type, str(body).encode("utf-8")
        if isinstance(result, (dict, list)):
            return 200, "application/json; charset=utf-8", json.dumps(
                result, ensure_ascii=False).encode("utf-8")
        return 200, "text/html; charset=utf-8", str(result).encode("utf-8")

    @staticmethod
    def _status_for(status: str) -> int:
        return {
            "DENIED": 403,
            "NOT_FOUND": 404,
            "STATE_CONFLICT": 409,
            "INVALID_ARGUMENT": 400,
        }.get(status, 500)

    @staticmethod
    def _error(code: str, message: str, status: str = "FAILED") -> dict:
        return {"error": {"code": code, "message": message, "status": status}}
