#!/usr/bin/env python3
"""Remote 五子棋 —— 一个跑在平台进程之外的第三方 LAP 应用。

它存在是为了证明方案里最要紧的两句话:

* <b>协议优先、SDK 非必须</b>(§75/§104)—— 它是纯 Python + 标准库, 平台是
  Java; 两者之间只有一个 HTTP 协议 + 一个 HMAC 签名, 没有任何共享代码。
* <b>应用不需要认识数字人</b>(§126)—— 它对"谁在下棋"的全部理解就是
  ``principal`` 这个形状; 真人、数字人、外部 Agent 都以同一个身份进来。

棋盘状态不落库 —— 它活在这个进程的内存里。这是刻意选的: REMOTE 应用的
资源(backing=APP_OWNED)本来就声明"状态在远端", 平台不持有它、也不该
替它操心持久化。一个要持久化的远端应用自己加自己的库, 协议不变。

与内置五子棋(``com.luxera.gomoku``)同规则 —— 15×15、五连即胜 —— 但
<b>id 不同</b>: ``com.luxera.remote-gomoku``。两个同能力的应用靠 URI
scheme 消歧, 这正是"发现链上能力相同的应用可以并存"的活例子。
"""

import os
import sys
import uuid

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "sdk", "python"))

from luxera_application import LapError, LapServer  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))

BOARD = 15
EMPTY = None
GAMES: dict[str, dict] = {}  # key: session id (从 target URI 解析) → 棋局

server = LapServer(secret_env="LAP_SERVICE_SECRET")


# ─────────────────────────── 棋局本体(纯函数, 与协议无关) ───────────────────────────

def new_game(opponent: str | None = None) -> dict:
    return {
        "board": [EMPTY] * (BOARD * BOARD),
        "seats": [None, opponent],  # seats[0] 执 X(开局者), seats[1] 执 O(对手)
        "turn": "X",
        "winner": None,
        "moves": 0,
    }


def winner_of(board: list) -> str | None:
    """五连检测 —— 四个方向, 每个起点只向右/下扫, 不重复。"""
    for row in range(BOARD):
        for col in range(BOARD):
            mark = board[row * BOARD + col]
            if mark is None:
                continue
            for d_row, d_col in ((0, 1), (1, 0), (1, 1), (1, -1)):
                if all(
                    0 <= row + d_row * i < BOARD and 0 <= col + d_col * i < BOARD
                    and board[(row + d_row * i) * BOARD + (col + d_col * i)] == mark
                    for i in range(5)
                ):
                    return mark
    return None


def board_full(board: list) -> bool:
    return all(cell is not None for cell in board)


# ─────────────────────────── 协议侧(动作 → 棋局操作) ───────────────────────────

def game_of(request) -> tuple[str, dict]:
    """从 target URI(gomoku-remote://match/{sessionId})解出会话与那一局。

    不认识 target 的形状 = 平台在按另一份 manifest 调我们 —— 那是配置错,
    该立刻炸响, 而不是默默开一盘谁也找不到的棋。
    """
    target = request.target or ""
    marker = "gomoku-remote://match/"
    if not target.startswith(marker):
        raise LapError("BAD_TARGET", f"target 不是本应用的形状: {target!r}", "INVALID_ARGUMENT")
    session = target[len(marker):]
    return session, GAMES.setdefault(session, new_game())


def seat_of(game: dict, principal_id: str) -> str | None:
    """这位 principal 执哪一色 —— 与他是人还是数字人无关, 只看座位。"""
    for index, seat in enumerate(game["seats"]):
        if seat == principal_id:
            return "X" if index == 0 else "O"
    return None


def sit_down(game: dict, principal_id: str):
    """没座位的人进来时给他找/建一个 —— X 的对面先空着等对手。"""
    if game["seats"][1] is None and game["seats"][0] != principal_id:
        game["seats"][1] = principal_id


def public_view(game: dict) -> dict:
    return {
        "board": game["board"],
        "seats": game["seats"],
        "turn": game["turn"],
        "winner": game["winner"],
        "moves": game["moves"],
    }


@server.action("game.create")
def create(request):
    _, game = game_of(request)
    me = request.principal.get("id", "")
    # 没人坐 X 位时, 开局者就是 X —— "预指定对手"只是把对面那个座位提前写上名字。
    if game["seats"][0] is None:
        game["seats"][0] = me
    opponent = request.input.get("opponentPrincipalId")
    if opponent and game["seats"][1] is None and opponent != game["seats"][0]:
        game["seats"][1] = opponent
    return {"state": public_view(game), "created": True}


@server.action("game.state")
def state(request):
    _, game = game_of(request)
    return {"state": public_view(game)}


@server.action("game.make_move")
def make_move(request):
    _, game = game_of(request)
    if game["winner"] is not None:
        raise LapError("GAME_OVER", "这一局已经分出胜负, 不能再落子", "STATE_CONFLICT")
    me = request.principal.get("id", "")
    sit_down(game, me)
    mark = seat_of(game, me)
    if mark is None:
        raise LapError("NOT_A_PLAYER", "你不在这局棋里 —— 先 game.create 开局, 或等开局者点名你",
                       "DENIED")
    if game["turn"] != mark:
        raise LapError("NOT_YOUR_TURN", f"现在轮到 {game['turn']}, 你执 {mark}", "STATE_CONFLICT")
    position = request.input.get("position")
    if not isinstance(position, int) or not (0 <= position < BOARD * BOARD):
        raise LapError("BAD_POSITION", f"格子序号必须是 0..{BOARD * BOARD - 1} 的整数",
                       "INVALID_ARGUMENT")
    if game["board"][position] is not None:
        raise LapError("CELL_TAKEN", "那个格子上已经有子了", "STATE_CONFLICT")
    game["board"][position] = mark
    game["moves"] += 1
    won = winner_of(game["board"])
    if won:
        game["winner"] = won
    elif board_full(game["board"]):
        game["winner"] = "DRAW"
    else:
        game["turn"] = "O" if mark == "X" else "X"
    return {"state": public_view(game), "moved": True, "mark": mark}


@server.action("game.surrender")
def surrender(request):
    _, game = game_of(request)
    me = request.principal.get("id", "")
    mark = seat_of(game, me)
    if mark is None:
        raise LapError("NOT_A_PLAYER", "你不在这局棋里, 无输可认", "DENIED")
    if game["winner"] is not None:
        return {"state": public_view(game), "surrendered": False, "reason": "already finished"}
    game["winner"] = "O" if mark == "X" else "X"
    return {"state": public_view(game), "surrendered": True}


# ─────────────────────────── 界面侧(GET, 不验签) ───────────────────────────

@server.page("/ui/{sessionId}")
def ui(request):
    """manifest 里 `ui.entry` 指的就是这个地址 —— 在这之前它是个 404。

    这一页是**浏览器直接打开**的, 所以它走不了 `/lap/actions:execute` 那条验签的路:
    用户的浏览器没有那把密钥。它读局面走的是下面那个 JSON 端点, 两者同一个进程、
    同一份内存, 但**不同的信任模型** —— 见 LapServer.page 的注释。
    """
    with open(os.path.join(HERE, "ui.html"), encoding="utf-8") as handle:
        return handle.read()


@server.page("/ui/{sessionId}/state")
def ui_state(request):
    """这一页的数据源。返回的形状与动作 `game.state` 的 state 完全一致 ——

    同一个局面在两个地方用两种形状表示, 是"界面显示的棋盘与 agent 看到的棋盘不一样"
    这类 bug 的唯一来源。所以这里直接复用 `public_view`。
    """
    game = GAMES.get(request.params["sessionId"])
    if game is None:
        # 还没人开过局 —— 空棋盘, 而不是 404: "这一局还没开始"是一个正常的状态。
        return public_view(new_game())
    return public_view(game)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8095
    # 端口让位: 验收脚本可能连跑多次, 上一次的进程要能被 SIGTERM 干净带走
    server.serve(host="127.0.0.1", port=port)
