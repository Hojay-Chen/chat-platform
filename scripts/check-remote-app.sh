#!/usr/bin/env bash
# LAP v2 R14: 远端应用端到端验收 —— 协议优先、SDK 非必须、DH 零改动。
#
# 这条脚本证明的是方案里最要紧的三句话:
#   1. 协议优先: 平台(Java)与远端五子棋(纯 Python 标准库)之间只有一个 HTTP 协议
#      + 一把 HMAC 密钥, 没有任何共享代码。Python 进程由本脚本自己起停, 失败不污染
#      check.sh / check-lap.sh —— 与 Java CI 完全隔离(方案 §75/§104)。
#   2. 一个第三方应用从零到上架: 开发者 API 建身份 → 认领 id → 写 manifest → 注册远端 →
#      真人开 Session → 落子 → 读回。中间任何一跳断了, 这里都会红。
#   3. 应用本体不需要认识数字人: 它对"谁在下棋"的全部理解就是 principal 这个形状。
#
# 用法: BASE=http://127.0.0.1:8081 LAP_MCP_SERVICE_KEY=<服务启动用的那个> \
#       [JAR_PID=<已在跑的服务 pid>] bash scripts/check-remote-app.sh
#
# JAR_PID 不给时, 本脚本自己起 jar(带 LAP_REMOTE_APPLICATIONS 注册远端五子棋),
# 验完关掉; 给了时只起 Python, 验完留 jar 原样 —— 供连续验收用。
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8081}"
PY=python3
FAIL=0
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GOMOKU_PORT="${GOMOKU_PORT:-8095}"
SECRET="check-remote-gomoku-secret"
MCP_KEY="${LAP_MCP_SERVICE_KEY:-}"

TMP="$(mktemp -d /tmp/check-remote-app.XXXXXX)"
cleanup() {
  [ -n "${PY_PID:-}" ] && kill "$PY_PID" 2>/dev/null || true
  [ -n "${JAR_OWN_PID:-}" ] && kill "$JAR_OWN_PID" 2>/dev/null || true
  rm -rf "$TMP"
}
trap cleanup EXIT

note() { echo "==> $*"; }
ok()   { echo "    ✓ $*"; }
fail() { echo "    ✗ $*"; FAIL=1; }

http() {
  local method="$1" url="$2" payload="${3:-}" key="${4:-}" auth="${5:-1}"
  local args=(-s -m 20 -o "$TMP/body" -D "$TMP/hdr" -w '%{http_code}' -X "$method" "$BASE$url")
  [ "$auth" = "1" ] && [ -n "$AUTH" ] && args+=(-H "Authorization: Bearer $AUTH")
  [ -n "$payload" ] && args+=(-H 'Content-Type: application/json' -d "$payload")
  [ -n "$key" ] && args+=(-H "Idempotency-Key: $key")
  curl "${args[@]}" || echo "000"
}
body() { cat "$TMP/body"; }
jq_() { body | $PY -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null || echo ""; }
encoded() { $PY -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=''))" "$1"; }

echo ""
echo "══════════ LAP 远端应用验收 (R14) ══════════"

# ── 0. 前提: Python 服务起来, 平台看得见它 ──
if [ -z "$MCP_KEY" ]; then
  echo "❌ 缺 LAP_MCP_SERVICE_KEY —— 开发者面(建开发者/认领 id)以 APPLICATION 身份鉴权需要它"
  exit 1
fi
if ! command -v "$PY" >/dev/null; then
  echo "❌ 找不到 $PY —— 远端五子棋本体是 Python, 这条验收没有它跑不了"
  exit 1
fi

note "起远端五子棋 (Python, 端口 $GOMOKU_PORT)"
LAP_SERVICE_SECRET="$SECRET" "$PY" "$ROOT/remote-apps/gomoku/app.py" "$GOMOKU_PORT" \
  > "$TMP/gomoku.log" 2>&1 &
PY_PID=$!
sleep 1
if curl -s -m 3 -o /dev/null "http://127.0.0.1:$GOMOKU_PORT/" 2>/dev/null; [ "$(kill -0 "$PY_PID" 2>/dev/null && echo up || echo down)" = "up" ]; then
  ok "Python 服务在跑 (pid=$PY_PID)"
else
  fail "Python 服务没起来: $(head -3 "$TMP/gomoku.log" 2>/dev/null)"
  echo ""; echo "❌ 验收未通过"; exit 1
fi

# ── 1. 平台: 自己起 or 用已经在跑的 ──
if [ -n "${JAR_PID:-}" ]; then
  note "复用在跑的服务 (pid=$JAR_PID)"
else
  note "起平台 (jar, 带 LAP_REMOTE_APPLICATIONS 注册远端五子棋)"
  JAR="${LAP_JAR:-$ROOT/chat/build/libs/chat-platform-1.0.0.jar}"
  if [ ! -f "$JAR" ]; then
    fail "找不到 $JAR —— 先 mvn -o package -DskipTests"
    echo ""; echo "❌ 验收未通过"; exit 1
  fi
  env LAP_MCP_SERVICE_KEY="$MCP_KEY" \
      LAP_REMOTE_APPLICATIONS="applications/remote-gomoku/1.0.0/application-manifest.json" \
      LAP_REMOTE_AUTH_REMOTE_GOMOKU="$SECRET" \
      java -jar "$JAR" > "$TMP/jar.log" 2>&1 &
  JAR_OWN_PID=$!
  for _ in $(seq 1 40); do
    curl -s -m 2 -o /dev/null "$BASE/api/v1/capabilities" && break
    sleep 2
  done
  curl -s -m 3 -o /dev/null "$BASE/api/v1/capabilities" \
    && ok "平台在跑 (pid=$JAR_OWN_PID)" \
    || { fail "平台没起来: $(tail -3 "$TMP/jar.log")"; echo ""; echo "❌ 验收未通过"; exit 1; }
fi

# ── 2. 登录 ──
note "登录取令牌"
CHECK_USER="${CHECK_USER:-haojie.chen.njau@gmail.com}"
CHECK_PASS="${CHECK_PASS:-20040719chj}"
AUTH=$(curl -s -m 15 -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$CHECK_USER\",\"password\":\"$CHECK_PASS\"}" \
  | $PY -c "import sys,json;print(json.load(sys.stdin).get('token',''))" 2>/dev/null || echo "")
if [ -z "$AUTH" ]; then
  fail "登录失败"; echo ""; echo "❌ 验收未通过"; exit 1
fi
ok "登录成功"

APP_ID="com.luxera.remote-gomoku"

# ── 断言 R1: 远端应用出现在发现链上(与内置五子棋同能力并存) ──
note "断言 R1: 远端五子棋在发现链上, 与内置实现同能力并存"
CODE=$(http GET "/api/v1/capabilities/game.play/applications")
[ "$CODE" = "200" ] && ok "GET 候选 200" || fail "状态码 $CODE"
BODY_TXT=$(body)
echo "$BODY_TXT" | grep -q "$APP_ID" && ok "候选里有 $APP_ID (REMOTE)" || fail "候选里没有远端五子棋"
echo "$BODY_TXT" | grep -q "com.luxera.gomoku" && ok "内置五子棋也在 —— 同能力两个实现并存" \
  || fail "内置五子棋从候选里消失了 (远端注册不该影响别人)"

# ── 断言 R2: 开发者 API —— 从零到一个应用行 ──
note "断言 R2: 开发者 API —— 建开发者 → 认领应用 id"
dev() {
  local method="$1" url="$2" payload="${3:-}"
  local args=(-s -m 20 -o "$TMP/body" -D "$TMP/hdr" -w '%{http_code}' -X "$method" "$BASE$url"
    -H "X-Mcp-Principal: APPLICATION:check-remote-dev"
    -H "X-Mcp-Service-Key: $MCP_KEY"
    -H "X-Correlation-Id: check-remote-app-$(date +%s%N)")
  [ -n "$payload" ] && args+=(-H 'Content-Type: application/json' -d "$payload")
  curl "${args[@]}" || echo "000"
}
STAMP=$(date +%s%N)
CODE=$(dev POST /api/v1/developers "{\"ownerUserId\":\"check-owner-$STAMP\",\"name\":\"远端验收工作室\"}")
[ "$CODE" = "201" ] && ok "POST /developers 201" || fail "建开发者状态码 $CODE"
[ "$(jq_ "d['status']")" = "ACTIVE" ] && ok "新开发者是 ACTIVE" || fail "status=$(jq_ "d['status']")"
# developerId 是平台生成的 UUID, 客户端要用它 —— 自己拼一个 id 去调是"客户端 bug",
# 那条路验证的是参数校验, 不是归属。所以下面两条: 真 id 走通, 假 id 才 404。
DEV_ID=$(jq_ "d['developerId']")
CLAIM_ID="com.check.remote-$STAMP"
[ -n "$DEV_ID" ] && ok "拿到 developerId=$DEV_ID" || fail "响应里没有 developerId"

CODE=$(dev POST "/api/v1/developers/$DEV_ID/applications" \
  "{\"applicationId\":\"$CLAIM_ID\",\"name\":\"验收临时应用\",\"category\":\"game\"}")
[ "$CODE" = "201" ] && ok "认领一个应用 id → 201 (从零到一个应用行)" || fail "认领状态码 $CODE: $(body | head -c 200)"
[ "$(jq_ "d['status']")" = "DRAFT" ] && ok "认领出来的应用是 DRAFT (还没有 manifest, 谈不上发布)" \
  || fail "status=$(jq_ "d['status']")"

# 同一个 id 再认领一次 —— 若这里放行, 第二个开发者一个 POST 就能抢走别人的应用 id。
CODE=$(dev POST "/api/v1/developers/$DEV_ID/applications" \
  "{\"applicationId\":\"$CLAIM_ID\",\"name\":\"抢注\",\"category\":\"game\"}")
[ "$CODE" = "409" ] && ok "再认领同一个 id → 409 APPLICATION_TAKEN" || fail "重认领状态码 $CODE"
[ "$(jq_ "d['error']['code']")" = "APPLICATION_TAKEN" ] && ok "code=APPLICATION_TAKEN" \
  || fail "code=$(jq_ "d['error']['code']")"

# 查无此人: 形状正确但不存在的 developerId —— 这是 404, 不是 400。
# 400 在对调用方说"你的载荷写错了", 于是门户会去改请求体, 而不是去换一个 developerId。
FAKE_DEV=$(python3 -c "import uuid;print(uuid.uuid4())")
CODE=$(dev POST "/api/v1/developers/$FAKE_DEV/applications" \
  "{\"applicationId\":\"com.check.nobody-$STAMP\",\"name\":\"验收临时应用\",\"category\":\"game\"}")
[ "$CODE" = "404" ] && ok "给不存在的开发者建应用 → 404 UNKNOWN_DEVELOPER" \
  || fail "状态码 $CODE (期望 404, 未知开发者不该建出应用)"

# ── 断言 R3: 真人开局 → 平台转发 → Python 落子 → 读回 ──
note "断言 R3: 真人在远端五子棋上开一局并落子"
CODE=$(http POST "/api/v1/applications/$APP_ID/sessions" '{}')
[ "$CODE" = "200" ] && ok "打开远端五子棋 200" || fail "打开状态码 $CODE"
SESSION_ID=$(jq_ "d['sessionId']")
URI="gomoku-remote://match/$SESSION_ID"
[ -n "$SESSION_ID" ] && ok "session=$SESSION_ID, target=$URI" || fail "响应里没有 sessionId"

CODE=$(http POST /api/v1/actions:execute "{\"action\":\"game.create\",\"target\":\"$URI\"}" "r14-create-$STAMP")
[ "$CODE" = "200" ] && ok "game.create 200 (平台→Python 往返)" || fail "开局状态码 $CODE: $(body | head -c 200)"

MOVE_KEY="r14-move-$STAMP"
MOVE_BODY="{\"action\":\"game.make_move\",\"target\":\"$URI\",\"input\":{\"position\":112}}"
CODE=$(http POST /api/v1/actions:execute "$MOVE_BODY" "$MOVE_KEY")
[ "$CODE" = "200" ] && ok "game.make_move 200 —— 一步棋跨了 Java→HTTP→HMAC→Python 四层" \
  || fail "落子状态码 $CODE: $(body | head -c 200)"

# 远端的状态要能被平台读回来: 平台那一行是远端状态的投影(见 RemoteActionHandler),
# 少了它, 真人 A 落的子真人 B 读不到 —— "所有参与者共享同一个 Resource"对远端应用就不成立。
RURI_Q=$(encoded "$URI")
CODE=$(http GET "/api/v1/resources?uri=$RURI_Q")
[ "$CODE" = "200" ] && ok "读资源 200 (远端状态已被平台投影成 resource 行)" || fail "读资源状态码 $CODE"
MARK=$(jq_ "d[0]['state']['board'][112]")
[ "$MARK" = "X" ] && ok "天元上落的是 X (远端状态经平台读回)" || fail "天元是 '$MARK' —— 落子没到远端"

# ── 断言 R4: 幂等跨四层 —— 同键重发不落第二子 ──
# 判据刻意不是"下一手能走"(那要另一个参与者才成立 —— 五子棋轮转, X 走完该 O),
# 而是"同一个键重发之后棋盘上的手数没变": 这才分得清"重放了一次作答"与"又执行了一次"。
note "断言 R4: 幂等键跨四层仍然成立"
BEFORE=$(jq_ "d[0]['state']['moves']")
CODE=$(http POST /api/v1/actions:execute "$MOVE_BODY" "$MOVE_KEY")
[ "$CODE" = "200" ] && ok "同键重发也 200 (重放, 不是重执行)" || fail "同键重发状态码 $CODE: $(body | head -c 200)"
CODE=$(http GET "/api/v1/resources?uri=$RURI_Q")
AFTER=$(jq_ "d[0]['state']['moves']")
[ "$AFTER" = "$BEFORE" ] && ok "手数仍是 $AFTER —— 四层里没有任何一层把重发当成第二次执行" \
  || fail "moves $BEFORE → $AFTER —— 幂等在某一层断了"

# ── 断言 R5: 协议错误形状 —— 远端说不, 平台按状态分类并把原话带回来 ──
# 平台不把远端的拒绝伪装成自己的: code 前缀 REMOTE_ 点明来源, 而远端的解释原样落进
# message。少任何一半, 排障的人手里就只剩一个 409。
note "断言 R5: 远端的拒绝以平台的状态码说出来"
CODE=$(http POST /api/v1/actions:execute \
  "{\"action\":\"game.make_move\",\"target\":\"$URI\",\"input\":{\"position\":112}}" "r14-taken-$STAMP")
[ "$CODE" = "409" ] && ok "同一位再走一手 → 409 (远端 409 → STATE_CONFLICT 分类)" \
  || fail "状态码 $CODE (期望 409): $(body | head -c 200)"
ERR=$(jq_ "d['error']['code']")
[ "$ERR" = "REMOTE_CONFLICT" ] && ok "code=REMOTE_CONFLICT (前缀点明它来自远端)" || fail "code=$ERR"
MSG=$(jq_ "d['error']['message']")
[ -n "$MSG" ] && ok "远端自己的解释被带回来了: $MSG" || fail "远端说的话丢了 (message 为空)"

# ── 断言 R6: 签名/密钥隔离 —— 密钥不在 manifest 里, 只在配置里 ──
note "断言 R6: manifest 无密钥"
MANIFEST_FILE="$ROOT/application/src/main/resources/applications/remote-gomoku/1.0.0/application-manifest.json"
grep -q "$SECRET" "$MANIFEST_FILE" && fail "密钥出现在 manifest 里" \
  || ok "manifest 里没有密钥 (authRef 只是名字)"
CODE=$(http GET "/api/v1/applications/$APP_ID")
[ "$(jq_ "d['applicationId']")" = "$APP_ID" ] && ok "应用详情读得到 (REMOTE 也一样)" \
  || fail "详情读不到: $(body | head -c 200)"

# ── 清理: 本局留下的行 ──
PSQL="psql -h 127.0.0.1 -U admin -d companion -tAc"
exec_sql() { PGPASSWORD=shared-secret $PSQL "$1" >/dev/null 2>&1 || true; }
exec_sql "delete from session_permission where participant_id in
            (select id from application_session_participant where session_id='$SESSION_ID')"
exec_sql "delete from application_session_participant where session_id='$SESSION_ID'"
exec_sql "delete from application_session where id='$SESSION_ID'"
# 这一轮开的那盘棋(远端状态投影出来的 resource 行)与认领出来的应用/开发者身份。
exec_sql "delete from resource where uri='$URI'"
exec_sql "delete from application where id='$CLAIM_ID'"
exec_sql "delete from developer where id='$DEV_ID'"

echo ""
if [ "$FAIL" = "0" ]; then
  echo "✅ 远端应用验收通过"
else
  echo "❌ 远端应用验收未通过"
  exit 1
fi
