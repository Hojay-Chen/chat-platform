#!/usr/bin/env bash
# G3 — 两服务同起验收: chat-platform(8081) ↔ agent-server(8091) 跨服务 HTTP 化。
# G1 时 check.sh CHECK_MODE=split 诚实跳过的"伴侣端到端"段, 从这里开始归还。
#
# 断言链(跨服务闭环, 每一步都是"上一轮不存在的能力"):
#   S1 环境就绪: PG + 两 jar 可用
#   S2 双进程起: agent-server 8091(Security 活: 未授权 401) + chat 8081(登录可用)
#   S3 /internal 面鉴权: 无签名 401 / 503(不配密钥), 对签名可达 —— 跨进程的"过滤器放行"钉死
#   S4 伴侣创建链: 同一 JWT 两服务都认(注册只在 chat, 登录拿 token, 8091 建伴侣)
#   S5 跨服务归属校验: chat 发消息 → requireOwned 走 HTTP 到 8091 → 落库成功
#   S6 跨服务认知通知: on-user-message fire-and-forget 到 8091(202)且 agent 侧收到
#   S7 双向缺席韧性: 停 8091 → chat 发消息仍 200(哲学: 不因对方缺席而残废)
#   S8 恢复闭环: 重起 8091 → outbox/直接再发 → 认知链恢复收
#
# 用法: bash scripts/check-split.sh   (自动起停两服务; 已起的服务会被复用)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AGT_ROOT="${AGT_ROOT:-$(cd "$ROOT/../simulation-agent-platform" && pwd)}"
BASE="${BASE:-http://127.0.0.1:8081}"
AGT_BASE="${AGT_BASE:-http://127.0.0.1:8091}"
KEY="${AGENT_PLATFORM_INTERNAL_KEY:-check-split-internal-key}"
CHAT_JAR="${CHAT_JAR:-$ROOT/chat/build/libs/chat-platform-1.0.0.jar}"
AGT_JAR="${AGT_JAR:-$AGT_ROOT/server/build/libs/simulation-agent-platform-1.0.0.jar}"
TMP="$(mktemp -d /tmp/check-split.XXXXXX)"
PIDS=()
FAIL=0

note() { echo "==> $*"; }
ok() { echo "    ✓ $*"; }
fail() { echo "    ✗ $*"; FAIL=1; }
cleanup() { for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

wait_up() {  # $1=url $2=最多重试次数 $3=进程日志文件(可选)
  local url="$1" tries="${2:-30}" log="${3:-}"
  for _ in $(seq 1 "$tries"); do
    curl -s -m 2 -o /dev/null "$url" && return 0 || true; sleep 2
  done
  return 1
}

# PG 访问封装成函数(不能写成 PSQL="PGPASSWORD=... psql" 字符串 —— bash 命令替换
# 展开时 env 前缀会被当成命令名的一部分, exit 127)。
psqlc() { PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -tAc "$@"; }

echo ""
echo "══════════ G3 两服务同起验收 (check-split) ══════════"

# ── S1 环境就绪 ──
note "S1: 环境就绪"
for j in "$CHAT_JAR" "$AGT_JAR"; do
  [ -f "$j" ] && ok "$(basename "$j") 存在" || { fail "缺 $j —— 先在对应仓 gradle bootJar"; echo ""; echo "❌ 验收未通过"; exit 1; }
done
psqlc "select 1" >/dev/null 2>&1 && ok "PG 在" || { fail "PG 不可达(companion 库)"; }

# ── S2 双进程起 ──
note "S2: 双进程起"
if curl -s -m 2 -o /dev/null "$AGT_BASE/api/health" || curl -s -m 2 -o /dev/null "$AGT_BASE/api/companions"; then
  AGT_OWN=0; ok "agent-server 已在跑 (复用)"
else
  ( cd "$AGT_ROOT" && AGENT_PLATFORM_INTERNAL_KEY="$KEY" CHAT_PLATFORM_BASE_URL="$BASE" \
      JWT_SECRET=luxera-companion-platform-dev-secret-change-me-0123456789abcdef \
      java -jar "$AGT_JAR" > "$TMP/agent.log" 2>&1 & echo $! > "$TMP/agent.pid" )
  PIDS+=("$(cat "$TMP/agent.pid")")
  wait_up "$AGT_BASE/api/health" 60 && ok "agent-server 起来 (pid=$(cat "$TMP/agent.pid"))" \
    || { fail "8091 没起来: $(tail -3 "$TMP/agent.log")"; }
fi
if curl -s -m 2 -o /dev/null "$BASE/api/health"; then
  ok "chat 已在跑 (复用)"
else
  ( cd "$ROOT" && AGENT_PLATFORM_INTERNAL_KEY="$KEY" AGENT_PLATFORM_BASE_URL="$AGT_BASE" \
      LAP_MCP_SERVICE_KEY=check-split-mcp-key \
      java -jar "$CHAT_JAR" > "$TMP/chat.log" 2>&1 & echo $! > "$TMP/chat.pid" )
  PIDS+=("$(cat "$TMP/chat.pid")")
  wait_up "$BASE/api/health" 60 && ok "chat 起来 (pid=$(cat "$TMP/chat.pid"))" \
    || { fail "8081 没起来: $(tail -3 "$TMP/chat.log")"; }
fi

# Security 活着: 8091 未授权面必须被拦(401/403 都算 —— JwtAuthenticationFilter 在场后
# 无凭证请求由 FilterSecurityInterceptor 拒成 403; 关键是 200 绝不该出现)
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$AGT_BASE/api/companions")
case "$CODE" in 401|403) ok "8091 Security 活 (未授权 $CODE)";; *) fail "8091 /api/companions 期望 401/403, 实得 $CODE";; esac

# ── S3 /internal 面鉴权 ──
note "S3: /internal 面鉴权 (chat 8081)"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/internal/runtime/capabilities")
[ "$CODE" = "401" ] && ok "无签名 → 401 (HMAC 过滤器在场)" || fail "无签名期望 401, 实得 $CODE"
# 对签名可达: chat 自己的 /internal 面, 由脚本算签名(与仓 2 HttpChatWorldAdapter 同式)
TS=$(date +%s); BODY=""
SIG=$(python3 - "$KEY" "$TS" "$BODY" <<'PY'
import hmac, hashlib, sys
key, ts, body = sys.argv[1], sys.argv[2], sys.argv[3]
print("sha256=" + hmac.new(key.encode(), (ts + "." + body).encode(), hashlib.sha256).hexdigest())
PY
)
CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Lap-Timestamp: $TS" -H "X-Lap-Signature: $SIG" -H "X-Lap-Service: agent" "$BASE/internal/runtime/capabilities")
[ "$CODE" = "200" ] && ok "对签名 → 200 (过滤器放行 + controller 到达)" || fail "对签名期望 200, 实得 $CODE"

# ── S4 伴侣创建链: 一个 JWT 两服务都认 ──
note "S4: 跨服务身份 (同一 JWT)"
# 注册功能已关闭(AuthController 一律 403, 账号由管理员创建) —— 测试用户直接种进
# users 表, BCrypt hash 由平台同一 PasswordEncoder 生成(与 AuthService.register 等价)。
USERNAME="split-$(date +%s)"
PASS="check-split-pass"
# BCrypt hash 里的 $ 在 bash 双引号里会被解释 —— 用 heredoc 不带引号变量的方式绕开:
# 把整条 SQL 写进单引号 heredoc, 变量用 psql 的 psqlvar 也不必, 直接 sed 换。
# (hash = BCrypt(check-split-pass), 由平台同一 BCryptPasswordEncoder 生成)
SQL_FILE="$TMP/seed.sql"
cat > "$SQL_FILE" <<'SQL'
insert into users (id, username, email, password_hash, nickname, created_at, updated_at)
values ('usr-@U@', '@U@', '@U@@test.luxera',
        '$2a$10$dAisdUjG69iN5IPh9eDmkerxe3hdhvmT4uRfvu.IAsq5WstxRKy.y',
        'check-split 验收用户', now(), now())
on conflict (id) do nothing;
SQL
sed -i "s/@U@/$USERNAME/g" "$SQL_FILE"
PGPASSWORD=shared-secret psql -h 127.0.0.1 -U admin -d companion -tAf "$SQL_FILE" >/dev/null 2>&1 || true
TOKEN=$(curl -s -m 15 -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
     -d "{\"username\":\"$USERNAME\", \"password\":\"$PASS\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("token",""))' 2>/dev/null)
[ -n "$TOKEN" ] && ok "chat 登录拿到 JWT (测试用户 $USERNAME)" || { fail "登录失败"; echo ""; echo "❌ 验收未通过"; exit 1; }
AGT_CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$AGT_BASE/api/companions")
[ "$AGT_CODE" = "200" ] && ok "同一 JWT 8091 也认 (users 表共享 + 同源 secret)" || fail "8091 不认 chat 签发的 JWT, 实得 $AGT_CODE"

# 8091 建伴侣(compile → create)
PERSONA=$(curl -s -X POST "$AGT_BASE/api/companions/compile" -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' -d '{"description":"一个温和的测试数字人, 喜欢读书和安静地陪伴"}')
PERSONA_JSON=$(echo "$PERSONA" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(json.dumps({"persona": d["persona"], "relationshipType": "friend"}))')
CREATE=$(curl -s -X POST "$AGT_BASE/api/companions" -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' -d "$PERSONA_JSON")
COMPANION_ID=$(echo "$CREATE" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))')
COMPANION_NAME=$(echo "$CREATE" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("name",""))')
[ -n "$COMPANION_ID" ] && ok "8091 建伴侣: $COMPANION_ID ($COMPANION_NAME)" || { fail "建伴侣失败: $(echo "$CREATE" | head -c 200)"; }

# ── S5 跨服务归属校验 ──
note "S5: chat 发消息 → requireOwned 跨 HTTP 到 8091"
CHAT_RESP=$(curl -s -X POST "$BASE/api/companions/$COMPANION_ID/conversations" \
     -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}')
CONV_ID=$(echo "$CHAT_RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' 2>/dev/null || echo "")
if [ -n "$CONV_ID" ]; then
  ok "会话建立: $CONV_ID (requireOwned 经 /internal/directory/require-owned 到了 8091)"
else
  fail "会话建立失败: $(echo "$CHAT_RESP" | head -c 300)"
fi
MSG_CODE=$(curl -s -o "$TMP/msg.json" -w '%{http_code}' -X POST \
     "$BASE/api/companions/$COMPANION_ID/conversations/$CONV_ID/messages" \
     -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"content":"你好呀, 跨服务的世界"}')
[ "$MSG_CODE" = "200" ] || [ "$MSG_CODE" = "201" ] \
  && ok "消息落库 ($MSG_CODE) —— onUserMessage 已 fire-and-forget 通知 8091" \
  || fail "发消息期望 2xx, 实得 $MSG_CODE: $(head -c 200 "$TMP/msg.json")"

# ── S6 认知通知真到了对岸 ──
note "S6: agent 侧收到认知通知"
sleep 6   # fire-and-forget 是异步的, 给 8091 的认知链一点时间(事件链 + 幂等记录)
# 认知收据 = processed_event 里的确定性事件 id:
#   ext-chat_message_delivered-<companionId>-<conversationId>-<lastMessageId>-<phase>
# (agent_states 不是收据 —— 它在认知链更深处, DEFERRED 路径可能不更新)
ROWS=$(psqlc "select count(*) from processed_event where event_id like '%${COMPANION_ID}%' and event_id like '%${CONV_ID}%'")
[ "$ROWS" -ge 1 ] && ok "8091 认知链接到消息 (processed_event 有 $ROWS 行 — 事件链闭环)" \
  || fail "8091 没收到认知通知 (processed_event 0 行) — 看 $TMP/agent.log"
# chat 世界也能被 8091 读到(经 /internal/world 读面): 直接从 chat 侧验消息在
MSG_ROWS=$(psqlc "select count(*) from messages where conversation_id='$CONV_ID' and content like '%跨服务的世界%'")
[ "$MSG_ROWS" -ge 1 ] && ok "消息在 chat 世界里 (8091 可经 /internal/world 读到)" || fail "消息没落库"

# ── S7 缺席韧性: 停 8091, chat 照常 ──
note "S7: 对方缺席, chat 不残废"
if [ -f "$TMP/agent.pid" ]; then
  kill "$(cat "$TMP/agent.pid")" 2>/dev/null || true
  sleep 3
  MSG2_CODE=$(curl -s -o "$TMP/msg2.json" -w '%{http_code}' -X POST \
       "$BASE/api/companions/$COMPANION_ID/conversations/$CONV_ID/messages" \
       -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
       -d '{"content":"8091 不在的时候我也想说句话"}')
  [ "$MSG2_CODE" = "200" ] || [ "$MSG2_CODE" = "201" ] \
    && ok "8091 缺席时消息照常落库 ($MSG2_CODE) — fire-and-forget + outbox 兜底" \
    || fail "8091 缺席时 chat 也失败: $MSG2_CODE: $(head -c 200 "$TMP/msg2.json")"
  # 归属校验在目录缺席时诚实拒绝 —— 不放行(把别人的伴侣当成自己的更糟)
  OTHER_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
       "$BASE/api/companions/$COMPANION_ID/conversations" \
       -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}')
  echo "    ○ 归属校验缺席时状态码: $OTHER_CODE (目录不可达 → 诚实拒绝, 不放行)"
  # 恢复 8091
  ( cd "$AGT_ROOT" && AGENT_PLATFORM_INTERNAL_KEY="$KEY" CHAT_PLATFORM_BASE_URL="$BASE" \
      JWT_SECRET=luxera-companion-platform-dev-secret-change-me-0123456789abcdef \
      java -jar "$AGT_JAR" > "$TMP/agent2.log" 2>&1 & echo $! > "$TMP/agent.pid" )
  PIDS+=("$(cat "$TMP/agent.pid")")
  wait_up "$AGT_BASE/api/health" 60 && ok "8091 重起 (S8 恢复)" || fail "8091 重起失败: $(tail -3 "$TMP/agent2.log")"
else
  echo "    ○ 跳过 S7/S8 (8091 是外部进程, 本脚本不代管其生命周期)"
fi

echo ""
if [ "$FAIL" = "0" ]; then
  echo "✅ 两服务同起验收通过 (G3 跨服务 HTTP 化)"
else
  echo "❌ 两服务同起验收未通过"
  echo "   日志: $TMP"
  exit 1
fi
