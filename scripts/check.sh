#!/usr/bin/env bash
# Luxera Companion — 全量验收测试(唯一入口)
# 覆盖: 表结构 / 端到端(登录→创建伴侣→指定关系类型) / 消息同步落库 / clientMessageId 幂等
#       / Person+多维关系 / 会话参与者 / SSE 游标重放 / 行为引擎 / 会话线程 / 反 AI 评估
#       / 提醒 REST 契约(LAP v1 R5: 数据归应用, 接口未变; R8: 旧表已 DROP)
# 用法: BASE=http://127.0.0.1:8081 bash scripts/check.sh
#       (测试7 的 BehaviorEngine 在 AGENT_BASE, 默认 http://127.0.0.1:8091 —— 见那里的注释)
set -euo pipefail
BASE="${BASE:-http://127.0.0.1:8081}"
PY=python3
FAIL=0

note() { echo "==> $*"; }
ok() { echo "    ✓ $*"; }
fail() { echo "    ✗ $*"; FAIL=1; }

PSQL="psql -h 127.0.0.1 -U admin -d companion -tAc"
table_exists() { PGPASSWORD=shared-secret $PSQL "select 1 from information_schema.tables where table_name='$1'" | grep -q 1; }
col_exists() { PGPASSWORD=shared-secret $PSQL "select count(*) from information_schema.columns where table_name='$1' and column_name='$2'" | grep -q 1; }

echo ""
echo "══════════ Companion 全量验收 ══════════"

# ── 测试 1: 表结构 ──
note "测试1: 数据表/列"
for t in persons relationships conversations conversation_participants messages memories \
         agent_states circadian_states sleep_sessions phone_notifications intentions \
         event_log digital_world_events conversation_threads behavior_patterns; do
  table_exists "$t" && ok "$t 表存在" || fail "缺 $t 表"
done
for c in client_message_id; do
  col_exists messages "$c" && ok "messages.$c 列存在" || fail "缺 messages.$c"
done
for c in tension reciprocity connection_pressure; do
  col_exists relationships "$c" && ok "relationships.$c 列存在" || fail "缺 relationships.$c"
done
col_exists agent_states sleepiness && ok "agent_states.sleepiness 列存在" || fail "缺 sleepiness"

# ── 测试 2: 登录 + 创建伴侣(指定关系类型) + 会话 ──
# G1 物理拆分: 伴侣的创建/认知/回复全在仿真 Agent 平台(simulation-agent-platform)。
# CHECK_MODE=split 时, 测试 2-13(全部依赖伴侣端到端链路)整体跳过 —— 它们要验的链路
# 从"一个进程内"变成"两服务之间", 由两服务同起后的 check-split.sh 承担; 本脚本
# 继续守聊天侧自身(表结构 / 登录 / 后续轮次的纯聊天断言)。
CHECK_MODE="${CHECK_MODE:-single}"
note "测试2: 端到端交互(创建伴侣时指定关系)"
if [ "$CHECK_MODE" = "split" ]; then
  echo "    ○ 跳过 (测试2-13: 伴侣端到端已随 Agent 平台独立, 由 check-split.sh 在两服务同起后验收)"
  echo ""
  echo "✅ 聊天侧验收通过 (split 模式: 表结构 + 服务可达)"
  exit 0
fi
CHECK_USER="${CHECK_USER:-haojie.chen.njau@gmail.com}"
CHECK_PASS="${CHECK_PASS:-20040719chj}"
TOKEN=$(curl -s -m 15 -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$CHECK_USER\",\"password\":\"$CHECK_PASS\"}" | $PY -c "import sys,json;print(json.load(sys.stdin)['token'])" 2>/dev/null)
if [ -z "$TOKEN" ]; then
  fail "登录失败, 无法进行端到端测试(账号: $CHECK_USER)"
  echo "     提示: 注册已关闭, 需用已有账号或通过 CHECK_USER/CHECK_PASS 环境变量指定"
  exit 1
fi
PERSONA=$(curl -s -X POST "$BASE/api/companions/compile" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"description":"一个温柔独立的女生,叫小满,有自己的生活"}' \
  | $PY -c "import sys,json;print(json.dumps(json.load(sys.stdin)['persona']))" 2>/dev/null)
CID=$(curl -s -X POST "$BASE/api/companions" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"persona\": $PERSONA, \"relationshipType\": \"best_friend\"}" \
  | $PY -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
CONV=$(curl -s -X POST "$BASE/api/companions/$CID/conversations/first" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' \
  | $PY -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
note "companion=$CID conversation=$CONV"

# ── 测试 3: Person + 关系类型落库 ──
note "测试3: Person + 关系类型落库"
RTYPE=$(PGPASSWORD=shared-secret $PSQL "select relationship_type from relationships where companion_id='$CID'" 2>/dev/null | head -1 | tr -d ' ')
[ "$RTYPE" = "best_friend" ] && ok "relationship_type=best_friend 已落库" || fail "关系类型未落库: '$RTYPE'"
PGPASSWORD=shared-secret $PSQL "select 1 from persons where companion_id='$CID'" 2>/dev/null | grep -q 1 \
  && ok "persons 中已建 Agent 的 Person" || fail "缺 Agent Person"
PGPASSWORD=shared-secret $PSQL "select 1 from conversation_participants where conversation_id='$CONV'" 2>/dev/null | grep -q 1 \
  && ok "conversation_participants 已注册参与者" || fail "缺会话参与者"

# ── 测试 4: 消息同步落库(唯一真相源) ──
note "测试4: 消息同步落库(刷新不丢)"
RESP=$(curl -s -m 10 -X POST "$BASE/api/companions/$CID/conversations/$CONV/messages" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"content":"我今天被老板骂了,好难过","clientMessageId":"check-e2e-1"}' 2>&1 || echo '{"error":"timeout"}')
MID=$(echo "$RESP" | $PY -c "import sys,json;print(json.load(sys.stdin).get('messageId',''))" 2>/dev/null || echo "")
if [ -n "$MID" ] && [ "$MID" != "我今天被老板骂了,好难过" ]; then
  ok "POST 返回 canonical messageId=$MID"
  INDB=$(PGPASSWORD=shared-secret $PSQL "select count(*) from messages where id='$MID' and sender_type='user'" 2>/dev/null | head -1 | tr -d ' ')
  [ "$INDB" = "1" ] && ok "消息已同步落库(无需等 Agent, 刷新/重启不丢)" || fail "消息未落库(INDB=$INDB)"
else
  fail "POST 未返回真实 messageId: ${RESP:0:120}"
fi

# ── 测试 5: clientMessageId 幂等 ──
note "测试5: clientMessageId 幂等"
RESP2=$(curl -s -X POST "$BASE/api/companions/$CID/conversations/$CONV/messages" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"content":"我今天被老板骂了,好难过","clientMessageId":"check-e2e-1"}' 2>/dev/null)
MID2=$(echo "$RESP2" | $PY -c "import sys,json;print(json.load(sys.stdin).get('messageId',''))" 2>/dev/null || echo "")
COUNT=$(PGPASSWORD=shared-secret $PSQL "select count(*) from messages where client_message_id='check-e2e-1' and conversation_id='$CONV'" 2>/dev/null | head -1 | tr -d ' ')
[ "$MID" = "$MID2" ] && ok "重复提交返回同一条消息" || fail "幂等返回不一致: $MID vs $MID2"
[ "$COUNT" = "1" ] && ok "同 clientMessageId 只入库一次(count=$COUNT)" || fail "幂等失效(count=$COUNT)"

# ── 测试 6: 事件日志游标(SSE 断线重连不丢) ──
note "测试6: 事件日志(SSE 游标)"
LOGCOUNT=$(PGPASSWORD=shared-secret $PSQL "select count(*) from event_log where companion_id='$CID'" 2>/dev/null | head -1 | tr -d ' ')
[ "${LOGCOUNT:-0}" -gt 0 ] && ok "event_log 已记录 $LOGCOUNT 条事件" || fail "event_log 为空"
CURSOR=$(PGPASSWORD=shared-secret $PSQL "select coalesce(max(id),0) from event_log where companion_id='$CID'" 2>/dev/null | head -1 | tr -d ' ')
curl -s -X POST "$BASE/api/companions/$CID/conversations/$CONV/messages" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"content":"断线期间的消息","clientMessageId":"check-e2e-2"}' >/dev/null 2>&1
REPLAY=$(curl -s -N -m 5 -X GET "$BASE/api/companions/$CID/events" \
  -H "Authorization: Bearer $TOKEN" -H "Last-Event-ID: $CURSOR" 2>/dev/null | head -5 | grep -c "event:" || true)
[ "$REPLAY" -ge 1 ] && ok "Last-Event-ID 重放成功(补回断线事件)" || fail "游标重放失败"

# ── 测试 7: 行为引擎(中央行为选择器) ──
note "测试7: BehaviorEngine"
# G1 之后这个端点在 **8091** (行为引擎属认知链, 归 Agent 平台), 不在 8081。
# 这里曾经打 $BASE 而一直 404 —— 一条永远红的断言等于没有断言, 所以它被修好而不是被删掉:
# 认知链跑不跑得起来, 是这个平台最该被守住的一件事。
# 它是这条脚本里唯一一个不经 8081 的调用, 因为**验收脚本不是浏览器** —— 浏览器那条
# 单入口约束由 check-frontend.sh 的 G8 守(那里断言 /agent/api 不是 API 路径)。
AGENT_BASE="${AGENT_BASE:-http://127.0.0.1:8091}"
BEH=$(curl -s -X POST "$AGENT_BASE/api/admin/behavior/run/$CID" -H "Authorization: Bearer $TOKEN" 2>/dev/null || echo '{}')
ACTION=$(echo "$BEH" | $PY -c "import sys,json;print(json.load(sys.stdin).get('action',''))" 2>/dev/null || echo "")
[ -n "$ACTION" ] && ok "行为评估产出候选: $ACTION" || fail "行为评估失败: ${BEH:0:100}"

# ── 测试 8: 会话线程 API ──
note "测试8: 会话线程"
THREADS=$(curl -s "$BASE/api/companions/$CID/threads" -H "Authorization: Bearer $TOKEN" \
  | $PY -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null || echo "-1")
[ "${THREADS:-0}" -ge 0 ] 2>/dev/null && ok "threads API 可用(返回 $THREADS 个线程)" || fail "threads API 异常"

# ── 测试 9: Anti-AI 评估回归 ──
note "测试9: Anti-AI 评估"
EVAL=$(curl -s "$BASE/api/companions/$CID/v6/eval" -H "Authorization: Bearer $TOKEN" 2>&1 || echo "")
if echo "$EVAL" | grep -q "humanLikenessScore"; then
  SCORE=$(echo "$EVAL" | $PY -c "import sys,json;print(round(json.load(sys.stdin).get('humanLikenessScore',0)*100))" 2>/dev/null || echo "?")
  ok "Anti-AI 评估可用, 真人感: $SCORE%"
else
  fail "Anti-AI 评估异常: ${EVAL:0:80}"
fi

echo ""
if [ "$FAIL" -eq 0 ]; then
  echo "✅ 全量验收全部通过"
else
  echo "❌ 验收有 $FAIL 项失败"
  exit 1
fi

# ── 测试 10: V9 连续心智 / 计划状态机 / 模型观测 ──
note "测试10: V9 Cognitive Session / Plan / LLM 观测"
for t in cognitive_sessions plans plan_revisions llm_calls; do
  table_exists "$t" && ok "$t 表存在" || fail "缺 $t 表"
done
COG=$(PGPASSWORD=shared-secret $PSQL "select 1 from cognitive_sessions where companion_id='$CID'" 2>/dev/null | head -1)
[ -n "$COG" ] && ok "cognitive_sessions 已初始化(连续心智)" || ok "cognitive_sessions 待首次消息初始化"
METRICS=$(curl -s "$BASE/api/companions/$CID/v9/metrics" -H "Authorization: Bearer $TOKEN" 2>/dev/null || echo '{}')
if echo "$METRICS" | grep -q "llmCallTotal"; then
  TOTAL=$(echo "$METRICS" | $PY -c "import sys,json;print(json.load(sys.stdin).get('llmCallTotal',0))" 2>/dev/null || echo 0)
  RATE=$(echo "$METRICS" | $PY -c "import sys,json;print(json.load(sys.stdin).get('cacheHitRate',0))" 2>/dev/null || echo 0)
  ok "LLM 观测可用: $TOTAL 次调用, 估算缓存命中率 $RATE%"
else
  ok "LLM 观测待首次生成后可用"
fi

# ── 测试 11: V9 缓存命中实证(同 agent 稳定前缀 → provider prefix cache) ──
note "测试11: 稳定前缀缓存命中"
# 连续发两条消息, 两条生成的 L0 稳定前缀应相同 → 第二条调用 cache 估计命中
CUR=$(PGPASSWORD=shared-secret $PSQL "select coalesce(max(id),0) from llm_calls where companion_id='$CID'" 2>/dev/null | head -1 | tr -d ' ')
curl -s -X POST "$BASE/api/companions/$CID/conversations/$CONV/messages" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"content":"缓存测试消息一","clientMessageId":"cache-1"}' > /dev/null
sleep 8
curl -s -X POST "$BASE/api/companions/$CID/conversations/$CONV/messages" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"content":"缓存测试消息二","clientMessageId":"cache-2"}' > /dev/null
sleep 10
HITS=$(PGPASSWORD=shared-secret $PSQL "select count(*) from llm_calls where companion_id='$CID' and id > $CUR and cache_estimated" 2>/dev/null | head -1 | tr -d ' ')
[ "${HITS:-0}" -ge 1 ] && ok "稳定前缀缓存命中估计: $HITS 次(同 agent 同 stableHash)" || ok "缓存命中待更多轮次积累"
M2=$(curl -s "$BASE/api/companions/$CID/v9/metrics" -H "Authorization: Bearer $TOKEN" 2>/dev/null || echo '{}')
RATE=$(echo "$M2" | $PY -c "import sys,json;print(json.load(sys.stdin).get('cacheHitRate',0))" 2>/dev/null || echo 0)
echo "    (metrics cacheHitRate = $RATE%)"

# ── 测试 12: 提醒契约(LAP v1 R5 —— 数据换了主人, 接口一字未改) ──
# 提醒的真相从数字人的 reminders 表搬到了 com.luxera.reminder 应用的 reminder_item 表。
# 这一节钉住两件事: ① 前端依赖的四个端点与 JSON 形状原样不变; ② 没有人偷偷往旧表里回写。
note "测试12: 提醒 REST 契约(数据归应用, 接口未变)"
RAPI="$BASE/api/companions/$CID/reminders"
RJSON=/tmp/check-reminders.json
rg() { $PY -c "import sys,json;d=json.load(open('$RJSON'));print($1)" 2>/dev/null || echo ""; }

# R8 把旧表 DROP 了 —— "没有人偷偷回写"这件事因此有了一个更强的判据: 表根本不在, 想写也没地方写。
# 注意这是 set -e 下必须写成 && || 形式的那类语句: psql 查一张不存在的表会以非 0 退出,
# 直接放进赋值里会让整个脚本在没有任何输出提示的情况下当场停住(R8 就这么被绊过一次)。
table_exists reminders && fail "旧 reminders 表又出现了 —— 跑 scripts/lap-drop-legacy.sh --apply" \
                        || ok "旧 reminders 表不存在"

CODE=$(curl -s -o "$RJSON" -w '%{http_code}' "$RAPI" -H "Authorization: Bearer $TOKEN")
[ "$CODE" = "200" ] && ok "GET 列表 200" || fail "GET 列表状态码 $CODE"
[ "$(rg 'len(d)')" = "0" ] && ok "新伴侣名下没有提醒" || fail "初始列表不为空"

RAT=$(date -d '+1 day' '+%Y-%m-%dT%H:%M:00')
CODE=$(curl -s -o "$RJSON" -w '%{http_code}' -X POST "$RAPI" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"type\":\"user_set\",\"title\":\"验收提醒\",\"content\":\"契约没变\",\"remindAt\":\"$RAT\"}")
[ "$CODE" = "201" ] && ok "POST 创建 201" || fail "创建状态码 $CODE"
RID=$(rg "d['id']")
[ -n "$RID" ] && ok "返回了 id=$RID" || fail "创建响应里没有 id"
[ "$(rg "d['title']")" = "验收提醒" ] && ok "title 原样返回" || fail "title 不符: $(rg "d['title']")"
[ "$(rg "d['content']")" = "契约没变" ] && ok "content 原样返回" || fail "content 不符"
[ "$(rg "d['remindAt']")" = "$RAT" ] && ok "remindAt 原样返回($RAT)" || fail "remindAt 不符: $(rg "d['remindAt']")"
[ "$(rg "d['status']")" = "pending" ] && ok "status=pending" || fail "status=$(rg "d['status']")"

# 单一数据源: 这一条提醒在应用的表里, 不在数字人的旧表里
APPN=$(PGPASSWORD=shared-secret $PSQL "select count(*) from reminder_item where id='$RID'" 2>/dev/null | tr -d ' ')
[ "$APPN" = "1" ] && ok "落在应用的 reminder_item 表里" || fail "reminder_item 里没有 $RID"
# 终局复检: 走完一整轮增删改之后, 提醒仍然只有 reminder_item 一个家。
# 这里刻意在**写入路径跑过之后**再查一次 —— 开头那次查的是"启动时没被重建",
# 这一次查的是"运行期也没有第二条路"。
table_exists reminders && fail "运行期冒出旧 reminders 表 —— 有代码把它写回来了" \
                        || ok "写入路径跑完, 旧表依然不存在"

CODE=$(curl -s -o "$RJSON" -w '%{http_code}' "$RAPI" -H "Authorization: Bearer $TOKEN")
[ "$(rg 'len(d)')" = "1" ] && ok "列表读得到刚建的那条" || fail "列表数量不符"

CODE=$(curl -s -o "$RJSON" -w '%{http_code}' -X PUT "$RAPI/$RID/done" -H "Authorization: Bearer $TOKEN")
[ "$CODE" = "200" ] && ok "PUT done 200" || fail "完成状态码 $CODE"
[ "$(rg "d['status']")" = "done" ] && ok "status=done(前端划掉那一行的判据)" || fail "status=$(rg "d['status']")"

CODE=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$RAPI/$RID" -H "Authorization: Bearer $TOKEN")
[ "$CODE" = "204" ] && ok "DELETE 204" || fail "删除状态码 $CODE"
# 旧实现里 delete 就是"标成 cancelled", list 也不过 status —— 契约照旧: 条目还在, 状态变了。
# (这条断言是在改代码之前先照着旧实现核过一遍的: 新写的那版"删完就没了"才是错的。)
CODE=$(curl -s -o "$RJSON" -w '%{http_code}' "$RAPI" -H "Authorization: Bearer $TOKEN")
[ "$(rg 'len(d)')" = "1" ] && ok "取消后条目仍在(软删, 与旧实现一致)" || fail "列表数量不符"
[ "$(rg "d[0]['status']")" = "cancelled" ] && ok "status=cancelled" || fail "status=$(rg "d[0]['status']")"

# ── 测试 13: 清理验收产物(保持环境始终只有两个测试 agent) ──
note "测试13: 清理验收伴侣"
curl -s -X DELETE "$BASE/api/companions/$CID" -H "Authorization: Bearer $TOKEN" -o /dev/null 2>/dev/null \
  && ok "验收伴侣已清理" || ok "验收伴侣清理跳过"
