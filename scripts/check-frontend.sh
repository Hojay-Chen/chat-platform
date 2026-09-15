#!/usr/bin/env bash
# G8 — 聊天前端验收: 单入口(只调聊天平台) + 服务端转发 + 端到端可达。
#
# G1 拆分把伴侣域(companions CRUD / memories / relationship / …)迁到 8091,
# 会话/消息/事件流留在 8081。G5 让**前端**按路径段分流(加 /agent 前缀直连 8091),
# G8 改为**后端**转发: 浏览器只认 companion.luxera.top 一个域名, /api/** 全进 8081,
# 伴侣域由 8081 转给 8091。本脚本验的就是这件事。
#
#   F1 环境: 两 jar + PG + 前端依赖装好
#   F2 双服务起: 8081 + 8091 同 key 同 JWT
#   F3 8081 登录拿 JWT
#   F4 单入口分流(经 8081, 带真 JWT) —— 本脚本的核心:
#        · 伴侣域写/读确实到了 8091(建伴侣、读详情、读记忆)
#        · 会话域留在 8081(auth/me、会话列表)
#        · /conversations/first 不再 404  ← G5 的回归点
#   F5 vite 代理单目标: /api → 8081; /agent/api 不再被代理
#
# 用法: bash scripts/check-frontend.sh   (自动起停 vite; 两服务用已在跑的或自起)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AGT_ROOT="${AGT_ROOT:-$(cd "$ROOT/../simulation-agent-platform" && pwd)}"
BASE="${BASE:-http://127.0.0.1:8081}"
AGT_BASE="${AGT_BASE:-http://127.0.0.1:8091}"
VITE="${VITE:-http://127.0.0.1:5173}"
KEY="${AGENT_PLATFORM_INTERNAL_KEY:-check-frontend-internal-key}"
CHAT_JAR="${CHAT_JAR:-$ROOT/chat/build/libs/chat-platform-1.0.0.jar}"
AGT_JAR="${AGT_JAR:-$AGT_ROOT/server/build/libs/simulation-agent-platform-1.0.0.jar}"
TMP="$(mktemp -d /tmp/check-frontend.XXXXXX)"
PIDS=()
FAIL=0

note() { echo "==> $*"; }
ok() { echo "    ✓ $*"; }
fail() { echo "    ✗ $*"; FAIL=1; }
# 递归杀整棵树 —— npm run dev 会 fork 出 node/vite, 只杀直接子进程会留下
# 孤儿继续占着 5173, 下一个脚本复用到它, 拿到的是上个脚本的状态。
kill_tree() {
  local pid="$1" child
  for child in $(pgrep -P "$pid" 2>/dev/null); do kill_tree "$child"; done
  kill "$pid" 2>/dev/null || true
}
cleanup() { for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill_tree "$p"; done; }
trap cleanup EXIT

wait_up() { local url="$1" tries="${2:-30}"; for _ in $(seq 1 "$tries"); do curl -s -m 2 -o /dev/null "$url" && return 0 || true; sleep 2; done; return 1; }

# 8091 **没有** /api/health 这个映射 —— 探它是 404。但 404 恰恰证明服务活着:
# 端口没人监听时 curl 拿不到任何状态码(000)。拿 /api/health 当探针会得出
# "8091 没起"的错误结论, 于是去起第二个实例、撞端口, 再报一次"没起来"。
# 判据是"有没有 HTTP 响应", 不是"状态码是不是 200"。
up8091() { [ "$(curl -s -o /dev/null -w '%{http_code}' -m 2 "$AGT_BASE/" 2>/dev/null || echo 000)" != "000" ]; }
psqlc() { PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -tAc "$@"; }

# 带 JWT 打 8081, 只回状态码
code() { curl -s -o /dev/null -w '%{http_code}' -m "${3:-15}" -H "Authorization: Bearer $TOKEN" "$BASE$1" ${2:+-X "$2"}; }

echo ""
echo "══════════ G8 聊天前端验收 (check-frontend) ══════════"

# ── F1 环境 ──
note "F1: 环境就绪"
for j in "$CHAT_JAR" "$AGT_JAR"; do
  [ -f "$j" ] && ok "$(basename "$j") 存在" || { fail "缺 $j"; echo ""; echo "❌ 验收未通过"; exit 1; }
done
psqlc "select 1" >/dev/null 2>&1 && ok "PG 在" || { fail "PG 不可达"; }
[ -d "$ROOT/frontend/node_modules" ] && ok "前端依赖已装" || { fail "缺 frontend/node_modules —— 先 npm ci"; echo ""; echo "❌ 验收未通过"; exit 1; }

# ── F2 双服务起 ──
note "F2: 双服务起(复用已在跑的)"
if ! curl -s -m 2 -o /dev/null "$BASE/api/health"; then
  # exec 让子 shell **变成** java —— 否则 `&` 绑定的是整个 `cd && java` 列表,
  # $! 拿到的是子 shell 的 pid, cleanup 杀掉子 shell 后 java 变孤儿继续占
  # 8081, 下一个脚本复用到它、撞上另一套配置, 报错却指向别处。
  ( cd "$ROOT" && exec env AGENT_PLATFORM_INTERNAL_KEY="$KEY" AGENT_PLATFORM_BASE_URL="$AGT_BASE" \
      LAP_MCP_SERVICE_KEY=check-frontend-mcp-key \
      java -jar "$CHAT_JAR" ) > "$TMP/chat.log" 2>&1 &
  PIDS+=("$!")
  wait_up "$BASE/api/health" 60 && ok "chat 起来" || fail "8081 没起来: $(tail -3 "$TMP/chat.log")"
else
  ok "chat 已在跑 (复用)"
fi
if ! up8091; then
  ( cd "$AGT_ROOT" && exec env AGENT_PLATFORM_INTERNAL_KEY="$KEY" CHAT_PLATFORM_BASE_URL="$BASE" \
      JWT_SECRET=luxera-companion-platform-dev-secret-change-me-0123456789abcdef \
      java -jar "$AGT_JAR" ) > "$TMP/agent.log" 2>&1 &
  PIDS+=("$!")
  for _ in $(seq 1 60); do up8091 && break || sleep 2; done
  up8091 && ok "agent-server 起来" || { fail "8091 没起来: $(tail -3 "$TMP/agent.log")"; }
else
  ok "agent-server 已在跑 (复用)"
fi

# ── F3 登录 ──
note "F3: 8081 登录拿 JWT"
USERNAME="fe-$(date +%s)"
PASS="check-frontend-pass"
SQL_FILE="$TMP/seed.sql"
cat > "$SQL_FILE" <<'SQL'
insert into users (id, username, email, password_hash, nickname, created_at, updated_at)
values ('usr-@U@', '@U@', '@U@@test.luxera',
        '$2a$10$tcHKwq9X69tMiqkxtA14oekFCLVaJtAnrOccvwemdZ39xG66nKwBa',
        'check-frontend 验收用户', now(), now())
on conflict (id) do nothing;
SQL
sed -i "s/@U@/$USERNAME/g" "$SQL_FILE"
PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -tAf "$SQL_FILE" >/dev/null 2>&1 || true
TOKEN=$(curl -s -m 15 -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASS\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("token",""))' 2>/dev/null)
[ -n "$TOKEN" ] && ok "8081 登录: JWT ${TOKEN:0:20}…" || { fail "8081 登录失败"; echo ""; echo "❌ 验收未通过"; exit 1; }

# ── F4 单入口分流(核心) ──
note "F4: 浏览器只打 8081 —— 伴侣域由 8081 服务端转给 8091"

# 4a. 会话域/auth 留在 8081
ME=$(code /api/auth/me)
[ "$ME" = "200" ] && ok "/api/auth/me → 8081 (200, 本地)" || fail "/api/auth/me 期望 200, 实得 $ME"

# 4b. 伴侣域写: POST /api/companions 建伴侣 —— G1 后 8081 没有这个端点, 必须转到 8091。
#     请求体形状照 CompanionCreate.tsx 的真实调用: persona 是**对象**不是字符串
#     (写成 {"persona":"x"} 会 400 —— 那不是转发的问题, 直连 8091 同样 400;
#     想在验收里分辨"转发错了"和"我构造错了", 就记住两者的响应必须一模一样)。
#     create 只给 persona.identity + relationship, 不走 /compile —— 那条要 LLM,
#     验收不该依赖外部 API。
CID=$(curl -s -m 20 -X POST "$BASE/api/companions" -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' \
        -d '{"persona":{"identity":{"name":"验收伴侣","gender":"female"},"relationship":{"type":"friend"}},"greeting":"你好"}' \
      | python3 -c 'import sys,json;print(json.load(sys.stdin).get("id",""))' 2>/dev/null)
if [ -n "$CID" ]; then
  ok "POST /api/companions → 8091 建伴侣成功 (id=$CID) —— 写入也走转发"
else
  fail "POST /api/companions 没拿到伴侣 id —— 伴侣域写入没到 8091"
  echo ""; echo "❌ 验收未通过(后续读取没有对象可验)"; exit 1
fi

# 4c. 伴侣域读: 详情 + 记忆(两个都在 8091)
DET=$(code "/api/companions/$CID")
[ "$DET" = "200" ] && ok "GET /api/companions/{id} → 8091 (200)" || fail "伴侣详情期望 200, 实得 $DET"
MEM=$(code "/api/companions/$CID/memories")
[ "$MEM" = "200" ] && ok "GET /api/companions/{id}/memories → 8091 (200)" || fail "记忆期望 200, 实得 $MEM"

# 4d. ★ G5 回归点: conversations/first 是 8091 的端点(开/复用会话, 回 ConversationView),
#     但路径段是 conversations —— G5 的段规则把它判给了 8081, 实测 404。这里断言它
#     **不再是 404**。不要求 200: 它会真的跑认知链(可能要 LLM), 只验"路由到位"(500 也算到位)。
FIRST=$(code "/api/companions/$CID/conversations/first" POST 10)
if [ "$FIRST" = "404" ]; then
  fail "/conversations/first 实得 404 —— 又被判给 8081 了(G5 的回归)"
elif [ "$FIRST" = "000" ]; then
  ok "/conversations/first 已到 8091 并开始响应(10s 截断, 无完整状态码)"
else
  ok "/conversations/first 已到 8091 (实得 $FIRST, 非 404)"
fi

# ── F5 vite 代理单目标 ──
note "F5: vite 单目标(/api → 8081); /agent/api 不再被代理"
( cd "$ROOT/frontend" && exec npm run dev ) > "$TMP/vite.log" 2>&1 &
PIDS+=("$!")
wait_up "$VITE" 40 || fail "vite dev 没起来: $(tail -3 "$TMP/vite.log")"

ME_V=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$VITE/api/auth/me")
[ "$ME_V" = "200" ] && ok "/api → 8081 (/api/auth/me 200)" || fail "/api 期望 200, 实得 $ME_V"

# 伴侣域经 vite 也必须通 —— 它同样是 /api 前缀, 由 8081 转发
MEM_V=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$VITE/api/companions/$CID/memories")
[ "$MEM_V" = "200" ] && ok "/api/companions/{id}/memories 经 vite 仍 200(8081 转发)" || fail "经 vite 记忆期望 200, 实得 $MEM_V"

# /agent/api/** 必须**不再**是有效路径。
#
# 判据是 Content-Type 不是状态码: vite 的 SPA 回退对任何未匹配路径都回
# index.html + **200** —— 于是"200"既可能是"旧代理还在"(200 + JSON)也可能是
# "已经删干净"(200 + HTML)。用状态码判会把正确状态报成失败(第一版就报错了)。
# 有意义的判据是"响应还是不是一份 API 的 JSON"。
AGENT_CT=$(curl -s -o /dev/null -w '%{content_type}' -H "Authorization: Bearer $TOKEN" "$VITE/agent/api/companions")
case "$AGENT_CT" in
  application/json*) fail "/agent/api/** 仍返回 JSON —— 旧的直连 8091 通道没清干净" ;;
  *) ok "/agent/api/** 不再是 API 路径 (Content-Type: ${AGENT_CT:-无}) —— 前端无直连 8091 的通道" ;;
esac

echo ""
if [ "$FAIL" = "0" ]; then
  echo "✅ 聊天前端验收通过 (G8 — 单入口 + 服务端转发 + 端到端可达)"
else
  echo "❌ 聊天前端验收未通过"
  echo "   日志: $TMP"
  exit 1
fi
