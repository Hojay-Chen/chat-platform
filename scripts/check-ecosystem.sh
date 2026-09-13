#!/usr/bin/env bash
# LAP v2 R15: 生态整体验收 —— §126 那条完整的链, 一条脚本走完。
#
# 第三方 Application → Catalog → Human Launch → Session → Share Link (join)
#   → Agent (MCP) → Action ×3 → 同一行 Resource → 事件 → DH 不改一行。
#
# 与 R14 的 check-remote-app.sh 的分工: 那条脚本证明"远端应用与平台之间协议成立";
# 这条脚本证明"这个应用真成了一个生态里的应用 —— 真人从分享链接的票进来,
# 数字人从 MCP 进来, 两类身份在同一行 resource 上落子, 互相读得到对方的子"。
# §106–§112 的七个 E2E 里, 五个的判据都在这条链上 (§106 共享世界 / §107 第三方零改动 /
# §108 参与者与邀请 / §112 MCP+真人同 resource); §109–§111 (意图→能力→应用) 属于 LLM 路线,
# 判据在 check-lap.sh 断言 11 与 AgentApplicationFlowTest —— 这里只核对接口面存在。
# 验收机只有一个登录账号(注册关闭): 真正的"第二个人"需要 CHECK_USER_B/CHECK_PASS_B
# 给出第二个账号, join 门那一跳的完整三人局才有 —— E3 处有说明。
#
# 用法: BASE=http://127.0.0.1:8081 LAP_MCP_SERVICE_KEY=<服务启动用的那个> \
#       bash scripts/check-ecosystem.sh
# 本脚本自己起 Python 五子棋与 jar (带 LAP_REMOTE_APPLICATIONS), 验完关掉。
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8081}"
PY=python3
FAIL=0
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GOMOKU_PORT="${GOMOKU_PORT:-8095}"
SECRET="check-remote-gomoku-secret"
MCP_KEY="${LAP_MCP_SERVICE_KEY:-}"
MCP_AGENT="com.luxera.harness-agent"
MCP_PRINCIPAL="AGENT:$MCP_AGENT"

TMP="$(mktemp -d /tmp/check-ecosystem.XXXXXX)"
cleanup() {
  [ -n "${PY_PID:-}" ] && kill "$PY_PID" 2>/dev/null || true
  [ -n "${JAR_PID:-}" ] && kill "$JAR_PID" 2>/dev/null || true
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
stamp() { date +%s%N; }

echo ""
echo "══════════ LAP 生态整体验收 (R15 · §126) ══════════"

# ── 0. 前提 ──
if [ -z "$MCP_KEY" ]; then
  echo "❌ 缺 LAP_MCP_SERVICE_KEY —— Agent(MCP)参与需要它"
  exit 1
fi
if ! command -v "$PY" >/dev/null; then
  echo "❌ 找不到 $PY —— 远端五子棋本体是 Python"
  exit 1
fi

note "起远端五子棋 (Python, 端口 $GOMOKU_PORT)"
LAP_SERVICE_SECRET="$SECRET" "$PY" "$ROOT/remote-apps/gomoku/app.py" "$GOMOKU_PORT" \
  > "$TMP/gomoku.log" 2>&1 &
PY_PID=$!
sleep 1
if [ "$(kill -0 "$PY_PID" 2>/dev/null && echo up || echo down)" = "up" ]; then
  ok "Python 服务在跑 (pid=$PY_PID)"
else
  fail "Python 服务没起来: $(head -3 "$TMP/gomoku.log" 2>/dev/null)"
  echo ""; echo "❌ 验收未通过"; exit 1
fi

note "起平台 (jar, 带 LAP_REMOTE_APPLICATIONS)"
JAR="${LAP_JAR:-$ROOT/chat/build/libs/chat-platform-1.0.0.jar}"
if [ ! -f "$JAR" ]; then
  fail "找不到 $JAR —— 先 mvn -o clean package -DskipTests"
  echo ""; echo "❌ 验收未通过"; exit 1
fi
env LAP_MCP_SERVICE_KEY="$MCP_KEY" \
    LAP_REMOTE_APPLICATIONS="applications/remote-gomoku/1.0.0/application-manifest.json" \
    LAP_REMOTE_AUTH_REMOTE_GOMOKU="$SECRET" \
    java -jar "$JAR" > "$TMP/jar.log" 2>&1 &
JAR_PID=$!
for _ in $(seq 1 40); do
  curl -s -m 2 -o /dev/null "$BASE/api/v1/capabilities" && break
  sleep 2
done
curl -s -m 3 -o /dev/null "$BASE/api/v1/capabilities" \
  && ok "平台在跑 (pid=$JAR_PID)" \
  || { fail "平台没起来: $(tail -3 "$TMP/jar.log")"; echo ""; echo "❌ 验收未通过"; exit 1; }

note "登录取令牌"
CHECK_USER="${CHECK_USER:-haojie.chen.njau@gmail.com}"
CHECK_PASS="${CHECK_PASS:-20040719chj}"
AUTH=$(curl -s -m 15 -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$CHECK_USER\",\"password\":\"$CHECK_PASS\"}" \
  | $PY -c "import sys,json;print(json.load(sys.stdin).get('token',''))" 2>/dev/null || echo "")
[ -z "$AUTH" ] && { fail "登录失败"; echo ""; echo "❌ 验收未通过"; exit 1; }
ok "登录成功 (Human A)"

APP_ID="com.luxera.remote-gomoku"
STAMP=$(stamp)

# ───────────── 断言 E1 (§107): 第三方应用在 Catalog 里, DH 不改一行 ─────────────
note "断言 E1: 远端五子棋在发现链上 —— 第三方上架没改任何平台代码"
CODE=$(http GET "/api/v1/capabilities/game.play/applications")
[ "$CODE" = "200" ] && ok "发现面 200" || fail "发现面状态码 $CODE"
body | grep -q "$APP_ID" && ok "$APP_ID 在 game.play 候选里" || fail "候选里没有 $APP_ID"
body | grep -q "com.luxera.gomoku" && ok "内置五子棋也在 —— 同能力并存" \
  || fail "内置五子棋从候选里消失了"
# §107 的另一半判据是结构性的: DH 模块在这条链路上一行没改 —— 由
# DhApplicationKnowledgeArchitectureTest 与 check-v10.sh 守, 这里不重复执行。

# ───────────── 断言 E2 (§126/§108): Human A 开局 ─────────────
note "断言 E2: Human A Launch 远端五子棋, 开一场 Session"
CODE=$(http POST "/api/v1/applications/$APP_ID/sessions" '{}')
[ "$CODE" = "200" ] && ok "POST /applications/$APP_ID/sessions 200" || fail "开局状态码 $CODE: $(body | head -c 200)"
SESSION_ID=$(jq_ "d['sessionId']")
OWNER_ID=$(jq_ "d['participant']['principalId']")
URI="gomoku-remote://match/$SESSION_ID"
[ -n "$SESSION_ID" ] && ok "session=$SESSION_ID (场主 principal=$OWNER_ID)" \
  || { fail "响应里没有 sessionId"; }

CODE=$(http POST /api/v1/actions:execute "{\"action\":\"game.create\",\"target\":\"$URI\"}" "e15-create-$STAMP")
[ "$CODE" = "200" ] && ok "game.create 200 (平台→远端往返)" || fail "开局状态码 $CODE: $(body | head -c 200)"

CODE=$(http POST /api/v1/actions:execute \
  "{\"action\":\"game.make_move\",\"target\":\"$URI\",\"input\":{\"position\":112}}" "e15-moveA-$STAMP")
[ "$CODE" = "200" ] && ok "Human A 落子天元 (position=112)" || fail "Human A 落子状态码 $CODE: $(body | head -c 200)"

# ───────────── 断言 E3 (§108 前半): 分享链接的票真的开得了门 ─────────────
note "断言 E3: 铸一张票, 票真的开得了那扇门"
CODE=$(http POST "/api/v1/sessions/$SESSION_ID/invitations" \
  "{\"role\":\"MEMBER\",\"maxUses\":1}")
[ "$CODE" = "200" ] && ok "铸票 200" || fail "铸票状态码 $CODE: $(body | head -c 200)"
TOKEN=$(jq_ "d['token']")
[ -n "$TOKEN" ] && ok "拿到明文票 (只此一次)" || fail "响应里没有 token"
JOIN_URL=$(jq_ "d['joinUrl']")
[ -n "$JOIN_URL" ] && ok "joinUrl=$JOIN_URL" || fail "响应里没有 joinUrl"

# 兑票: 浏览器点开 /join/{token} 的那扇门。此端点要 JWT —— 分享链接表达的是
# "加入这一场"(§14), 不是匿名放行; 持票人仍是登录用户。
# 验收机只有一个账号(注册已关闭), 默认拿 Human A 自己的 JWT 兑票: join 幂等 ——
# 同一个人兑同一张票不烧名额, 这条验证的是"票开得了门"(200), 不是"第二个人"。
# 真正的 Human B: CHECK_USER_B/CHECK_PASS_B 给出第二个账号时, 这一跳就是完整的
# "第二个人从分享链接进来"(§108 前半), E5 的参与人数也随之从 2 变 3。
if [ -n "${CHECK_USER_B:-}" ] && [ -n "${CHECK_PASS_B:-}" ]; then
  AUTH_B=$(curl -s -m 15 -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$CHECK_USER_B\",\"password\":\"$CHECK_PASS_B\"}" \
    | $PY -c "import sys,json;print(json.load(sys.stdin).get('token',''))" 2>/dev/null || echo "")
  if [ -n "$AUTH_B" ]; then
    AUTH_SAVE="$AUTH"; AUTH="$AUTH_B"
    CODE=$(http POST "/api/v1/join/$TOKEN" '{}')
    AUTH="$AUTH_SAVE"
    [ "$CODE" = "200" ] && ok "Human B 从分享链接兑票进门 200 —— 完整的第二人一跳" \
      || fail "Human B 兑票状态码 $CODE: $(body | head -c 200)"
    EXPECT_PARTS=3
    PARTS_DESC="Human A(场主) + Human B(分享链接) + Agent —— §108 终局"
  else
    fail "CHECK_USER_B 登录失败 —— 退回单账号模式"
    CODE=$(http POST "/api/v1/join/$TOKEN" '{}')
    [ "$CODE" = "200" ] || fail "兑票状态码 $CODE: $(body | head -c 200)"
    EXPECT_PARTS=2
    PARTS_DESC="Human A(场主, 幂等兑票不加行) + Agent"
  fi
else
  CODE=$(http POST "/api/v1/join/$TOKEN" '{}')
  [ "$CODE" = "200" ] && ok "兑票进门 200 (幂等: 已在场者兑票不烧名额)" \
    || fail "兑票状态码 $CODE: $(body | head -c 200)"
  EXPECT_PARTS=2
  PARTS_DESC="Human A(场主, 幂等兑票不加行) + Agent"
fi
J_PRINCIPAL=$(jq_ "d['principalId']")
[ -n "$J_PRINCIPAL" ] && ok "兑票响应里有参与者身份: $J_PRINCIPAL" || fail "兑票响应里没有 principalId"

# ───────────── 断言 E4 (§108 后半): Agent 受邀进场, 再经 MCP 落子 ─────────────
note "断言 E4: 定向邀请 Agent → 进场 → MCP 应手"
# 数字人收到的是一封"定向邀请"(平台事件, targetType=AGENT): 它接受后走 port.joinByInvitation
# —— 与真人点开 /join/{token} 是同一扇门(R13 断言 20 在真实服务上验过那条链: 事件跨四层
# 进它的邮箱)。它的进场由进程内完成; 这里的 participant 行按 check-lap.sh 断言 14 的
# 同一惯例落(AGENT 身份在 REST 面没有第二个入口 —— 这正是"Agent 没有 Application
# 专用 API"那条原则), 然后 Agent 经 MCP 落子: MCP 是 Gateway 的一个 Adapter,
# 落子走的是与 Human 完全同一条执行链。
CODE=$(http POST "/api/v1/sessions/$SESSION_ID/invitations" \
  "{\"role\":\"MEMBER\",\"maxUses\":1,\"targetType\":\"AGENT\",\"targetId\":\"$MCP_AGENT\"}")
[ "$CODE" = "200" ] && ok "定向邀请 Agent 200" || fail "定向邀请状态码 $CODE: $(body | head -c 200)"
AGENT_TOKEN=$(jq_ "d['token']")
[ -n "$AGENT_TOKEN" ] && ok "拿到定向票 (只此一次)" || fail "定向票响应里没有 token"

# AGENT participant 行 + 授权行 (与 check-lap.sh seat_agent 同一惯例): AGENT 身份
# 在 REST 面没有第二个入口, 授权行照抄场主的 —— Agent 经 Gateway 拿到的授权与
# 主人相同(能力级 EXECUTE), 后面的 NOT_AUTHORIZED 判据(check-lap.sh 断言 9a)同样成立。
PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -tAc \
  "insert into application_session_participant
     (id, session_id, principal_type, principal_id, role, status, permission_profile, joined_at)
   values (gen_random_uuid()::text, '$SESSION_ID', 'AGENT', '$MCP_AGENT', 'MEMBER', 'ACTIVE', 'MEMBER', now())" \
  >/dev/null 2>&1 || fail "Agent participant 行没落上"
PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -tAc \
  "insert into session_permission
     (id, participant_id, capability_id, action_id, permission_level, risk_ceiling, created_at)
   select gen_random_uuid()::text,
          (select id from application_session_participant
            where session_id='$SESSION_ID' and principal_type='AGENT' and principal_id='$MCP_AGENT'),
          g.capability_id, g.action_id, g.permission_level, g.risk_ceiling, now()
   from session_permission g
   where g.participant_id = (select id from application_session_participant
                               where session_id='$SESSION_ID'
                                 and principal_type='HUMAN' and principal_id='$OWNER_ID')" \
  >/dev/null 2>&1 && ok "Agent 以 MEMBER 身份进场, 授权照抄场主 (能力级 EXECUTE)" \
  || fail "Agent 的授权行没落上 (照抄场主失败)"

# Agent 的第一手棋经 MCP。工具名: 短名 = id 最后一段 —— remote-gomoku 与内置 gomoku
# 的短名各是 "remote-gomoku" 与 "gomoku", census 各为 1, 不撞 → 用短名 remote-gomoku.game_make_move。
# (第一版这里写了全名 com_luxera_remote_gomoku.… 而吃 TOOL_NOT_FOUND: 全名只在短名撞车时才出现,
#  "两个五子棋并存"并不构成撞车 —— 那条退化规则有测试钉住, 这里是它的反例面。)
MCP_BODY="{\"jsonrpc\":\"2.0\",\"id\":15,\"method\":\"tools/call\",\"params\":{\"name\":\"remote-gomoku.game_make_move\",\"arguments\":{\"target\":\"$URI\",\"position\":7}}}"
mcp() {
  curl -s -m 20 -o "$TMP/body" -w '%{http_code}' -X POST "$BASE/mcp" \
    -H "Content-Type: application/json" \
    -H "X-Mcp-Principal: $MCP_PRINCIPAL" \
    -H "X-Mcp-Service-Key: $MCP_KEY" \
    -H "X-Correlation-Id: e15-mcp-$1" \
    -H "Idempotency-Key: e15-mcp-$1" \
    -d "$MCP_BODY"
}
CODE=$(mcp "$(stamp)")
[ "$CODE" = "200" ] && ok "MCP tools/call 200 (remote-gomoku.game_make_move)" || fail "MCP 状态码 $CODE: $(body | head -c 200)"
ISERR=$(jq_ "d['result']['isError']")
if [ "$ISERR" = "False" ]; then
  ok "Agent 的落子被接受 (isError=false)"
else
  fail "Agent 落子被拒: $(body | head -c 300)"
fi

# ───────────── 断言 E5 (§106/§112): 一行 resource, 两类身份, 互相读得到 ─────────────
note "断言 E5: 共享世界 —— Human 与 Agent 在同一行 resource 上"
RURI_Q=$(encoded "$URI")
CODE=$(http GET "/api/v1/resources?uri=$RURI_Q")
[ "$CODE" = "200" ] && ok "读资源 200" || fail "读资源状态码 $CODE"
BOARD112=$(jq_ "d[0]['state']['board'][112]")
BOARD7=$(jq_ "d[0]['state']['board'][7]")
MOVES=$(jq_ "d[0]['state']['moves']")
PARTS=$(PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -tAc \
  "select count(*) from application_session_participant where session_id='$SESSION_ID'" 2>/dev/null || echo "?")
[ "$BOARD112" = "X" ] && ok "board[112]=X —— Human A 的子, 平台读得到" || fail "board[112]=$BOARD112"
[ "$BOARD7" = "O" ] && ok "board[7]=O —— Agent 经 MCP 的子, 与 Human A 同一盘棋" || fail "board[7]=$BOARD7"
[ "$MOVES" = "2" ] && ok "手数=2 (一次 Human + 一次 Agent, 没有多余的执行)" || fail "moves=$MOVES"
[ "$PARTS" = "$EXPECT_PARTS" ] 2>/dev/null && ok "一场会话, $PARTS 位参与者 ($PARTS_DESC)" \
  || fail "参与者行数 $PARTS (期望 $EXPECT_PARTS)"

# 225 格整盘读回: "共享同一个 Resource"的判据是任何一位参与者读到的投影都完整一致 ——
# Human A 从 REST 读到的, 与 Agent 落子时经 Gateway 读到的是同一行。
BOARD_TXT=$(jq_ "''.join(x or '.' for x in d[0]['state']['board'])")
[ -n "$BOARD_TXT" ] && ok "225 格棋盘整体读回 (投影完整)" || fail "棋盘读不全"

# ───────────── 断言 E6 (§109–§111 接口面): 意图→能力→应用 的三个端点 ─────────────
note "断言 E6: LLM 路线的接口面 —— 能力→应用→动作 逐级收窄的三级端点"
CODE=$(http GET "/api/v1/capabilities")
[ "$CODE" = "200" ] && body | grep -q "game.play" && ok "GET /capabilities 有 game.play" \
  || fail "能力列表里没有 game.play"
CODE=$(http GET "/api/v1/applications/$APP_ID/actions")
[ "$CODE" = "200" ] && body | grep -q "game.make_move" && ok "GET /actions 有 game.make_move" \
  || fail "动作列表里没有 game.make_move"
# 跨能力隔离(§110)的判据: reminder.manage 的候选里不能出现 game.play 应用。
CODE=$(http GET "/api/v1/capabilities/reminder.manage/applications")
[ "$CODE" = "200" ] && ! body | grep -q "remote-gomoku" && ok "reminder.manage 候选里没有游戏 (能力隔离)" \
  || fail "提醒能力里混进了游戏应用"

# ───────────── 断言 E7 (§126 收口): 每一次动作都留了痕, 两种身份各一行 ─────────────
note "断言 E7: 审计账本 —— 同一场棋, 两种身份的行动都在"
LOG_HUMAN=$(PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -tAc \
  "select count(*) from application_action_log where resource_uri='$URI' and principal_type='HUMAN'" 2>/dev/null || echo "0")
LOG_AGENT=$(PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -tAc \
  "select count(*) from application_action_log where resource_uri='$URI' and principal_type='AGENT'" 2>/dev/null || echo "0")
[ "${LOG_HUMAN:-0}" -ge 1 ] && ok "HUMAN 的动作在账上 ($LOG_HUMAN 行)" || fail "HUMAN 账上 $LOG_HUMAN 行"
[ "${LOG_AGENT:-0}" -ge 1 ] && ok "AGENT 的动作在账上 ($LOG_AGENT 行) —— 同一场棋, 两种身份共用一条审计链" \
  || fail "AGENT 账上 $LOG_AGENT 行"

# ───────────── 清理 ─────────────
PSQL="psql -h 127.0.0.1 -U admin -d companion -tAc"
exec_sql() { PGPASSWORD=shared-secret $PSQL "$1" >/dev/null 2>&1 || true; }
exec_sql "delete from session_permission where participant_id in
            (select id from application_session_participant where session_id='$SESSION_ID')"
exec_sql "delete from session_invitation where session_id='$SESSION_ID'"
exec_sql "delete from application_session_participant where session_id='$SESSION_ID'"
exec_sql "delete from application_session where id='$SESSION_ID'"
exec_sql "delete from resource where uri='$URI'"
exec_sql "delete from application_action_log where resource_uri='$URI'"

echo ""
if [ "$FAIL" = "0" ]; then
  echo "✅ 生态整体验收通过 (§126 全链路)"
else
  echo "❌ 生态整体验收未通过"
  exit 1
fi
