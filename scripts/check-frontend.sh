#!/usr/bin/env bash
# G5 — 聊天前端验收: 双服务分流 + 端到端可达。
#
# G1 拆分把伴侣域(companions CRUD / memories / relationship / …)迁到 8091,
# 会话/消息/事件流留在 8081。前端 client.ts 的 route() 按"路径段"判定加
# /agent 前缀, vite 代理双目标分流。本脚本起两服务, 经 vite 代理真验:
#
#   F1 环境: 两 jar + PG + 前端依赖装好
#   F2 双服务起: 8081 + 8091 同 key 同 JWT(复用 G3 check-split 的起服务方式)
#   F3 8081 面可达: /api/health + 登录拿 JWT
#   F4 8091 面可达: 同一 JWT GET /api/companions 列表(经 /agent/api 前缀)
#   F5 vite 代理分流: 起 vite dev, /agent/api → 8091 / /api → 8081
#      — 用 route() 的判定逻辑直连两服务(不经浏览器, 直接 curl 经 vite)
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

echo ""
echo "══════════ G5 聊天前端验收 (check-frontend) ══════════"

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
  wait_up "$BASE/api/health" 60 && ok "chat 起来" || fail "8081 没起来"
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

# ── F3 8081 面: 登录 ──
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

# ── F4 8091 面: 同一 JWT GET /api/companions ──
note "F4: 同一 JWT 8091 GET /api/companions"
AGT_CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$AGT_BASE/api/companions")
[ "$AGT_CODE" = "200" ] && ok "8091 认同一 JWT (GET /api/companions 200)" || fail "8091 期望 200, 实得 $AGT_CODE"

# ── F5 vite 代理分流 ──
note "F5: vite 代理分流(/agent/api→8091, /api→8081)"
( cd "$ROOT/frontend" && exec npm run dev ) > "$TMP/vite.log" 2>&1 &
PIDS+=("$!")
wait_up "$VITE" 40 || { fail "vite dev 没起来: $(tail -3 "$TMP/vite.log")"; }

# /api/auth/me → 应到 8081(经 vite 代理 /api)
ME_CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$VITE/api/auth/me")
[ "$ME_CODE" = "200" ] && ok "/api → 8081 (/api/auth/me 200)" || fail "/api 期望 200, 实得 $ME_CODE"

# /agent/api/companions → 应到 8091(经 vite 代理 /agent/api, rewrite 去 /agent)
COMP_CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$VITE/agent/api/companions")
[ "$COMP_CODE" = "200" ] && ok "/agent/api → 8091 (/api/companions 200)" || fail "/agent/api 期望 200, 实得 $COMP_CODE"

# 反向验证: /api/companions(不加 /agent)→ 应该 404(因为 8081 没这个端点)
WRONG_CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$VITE/api/companions")
[ "$WRONG_CODE" = "404" ] && ok "/api/companions(无 /agent 前缀) → 8081 → 404 (分流正确: 该端点在 8091)" \
  || fail "/api/companions 期望 404(8081 没此端点), 实得 $WRONG_CODE — 分流错了"

echo ""
if [ "$FAIL" = "0" ]; then
  echo "✅ 聊天前端验收通过 (G5 — 双服务分流 + 端到端可达)"
else
  echo "❌ 聊天前端验收未通过"
  echo "   日志: $TMP"
  exit 1
fi
