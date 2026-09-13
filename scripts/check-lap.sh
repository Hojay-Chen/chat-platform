#!/usr/bin/env bash
# LAP v2 应用生态 — 端到端验收。
#
# 为什么必须有这个脚本:
#   scripts/check.sh 覆盖的是聊天/数字人链路, 对应用平台「零覆盖」——
#   这就是为什么「mvn test 全绿」在应用平台上什么也保护不了。
#   本脚本是 ActionGateway / Manifest / Resource / 幂等 / 会话与参与者唯一的端到端守卫。
#
# v1 → v2 改掉了这个脚本里最要紧的那几条断言, 因为它们守的东西换了:
#   「装了没有」→「在不在这一局里」; NOT_INSTALLED → NOT_A_PARTICIPANT;
#   INSTALLATION_INACTIVE → PARTICIPANT_INACTIVE; 安装入口 → 打开即开会话。
# 断言 1 里那几条"反向断言"(v1 的表/列必须<em>不在</em>)是这次重构唯一不可逆的一步的守卫。
#
# 断言编号沿用实施计划, 未到轮次的先跳过并打印原因(R5 起陆续打开, R8 全部打开)。
#
# 用法: BASE=http://127.0.0.1:8081 LAP_MCP_SERVICE_KEY=<服务启动时用的那个> bash scripts/check-lap.sh
#
# LAP_MCP_SERVICE_KEY 是断言 14(MCP 适配器)需要的, 不是可选的礼貌参数:
# 服务端那把密钥留空 = MCP 完全关闭(每个请求 403), 而一个关闭的 MCP 与一个工作的 MCP 在
# 「没有断言」这件事上长得一模一样。所以脚本拿不到密钥时**报错**而不是跳过 —— 跳过会让
# 这一整轮验收在适配器根本没上线的情况下显示为通过。
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8081}"
PY=python3
FAIL=0
SKIPPED=0

TMP="$(mktemp -d /tmp/check-lap.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT

note() { echo "==> $*"; }
ok()   { echo "    ✓ $*"; }
fail() { echo "    ✗ $*"; FAIL=1; }
skip() { echo "    ○ 跳过 ($*)"; SKIPPED=$((SKIPPED + 1)); }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

PSQL="psql -h 127.0.0.1 -U admin -d companion -tAc"
table_exists() { PGPASSWORD=shared-secret $PSQL "select 1 from information_schema.tables where table_name='$1'" | grep -q 1; }
col_exists() { PGPASSWORD=shared-secret $PSQL "select 1 from information_schema.columns where table_name='$1' and column_name='$2'" | grep -q 1; }
col_nullable() { PGPASSWORD=shared-secret $PSQL "select is_nullable from information_schema.columns where table_name='$1' and column_name='$2'" | tr -d ' '; }
sql() { PGPASSWORD=shared-secret $PSQL "$1" | tr -d ' '; }
exec_sql() { PGPASSWORD=shared-secret $PSQL "$1" >/dev/null; }

encoded() { $PY -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=''))" "$1"; }
stamp() { date +%s%N; }

APP_ID="com.luxera.tictactoe"
AUTH=""

# HTTP 助手: 打印状态码, 正文留在 $TMP/body, 响应头留在 $TMP/hdr
http() {
  local method="$1" url="$2" payload="${3:-}" key="${4:-}"
  local args=(-s -m 20 -o "$TMP/body" -D "$TMP/hdr" -w '%{http_code}' -X "$method" "$BASE$url")
  if [ -n "$AUTH" ]; then args+=(-H "Authorization: Bearer $AUTH"); fi
  if [ -n "$payload" ]; then args+=(-H 'Content-Type: application/json' -d "$payload"); fi
  if [ -n "$key" ]; then args+=(-H "Idempotency-Key: $key"); fi
  curl "${args[@]}" || echo "000"
}
body() { cat "$TMP/body"; }
jq_() { body | $PY -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null || echo ""; }

echo ""
echo "══════════ LAP v2 应用生态验收 ══════════"

# ── 断言 1: 表结构 ──
note "断言 1: LAP 表结构"
for t in developer application application_version capability application_capability \
         application_session application_session_participant session_permission resource \
         subscription action_invocation application_action_log lap_outbox; do
  table_exists "$t" && ok "$t 表存在" || fail "缺 $t 表"
done

# v2 的两张被删掉的表 —— 与上面那条方向相反, 断言的是"它们不在"。
# 为什么值得单列: 删表是这次重构里唯一不可逆的动作, 而一张还留着的 installation 表
# **不会让任何东西报错** —— 它只会让下一个读到它的人以为安装还是一个概念。
for t in installation permission_grant; do
  table_exists "$t" && fail "v2 已删除的表 $t 还在 — 跑 scripts/lap-v2-reset.sh --apply" \
                     || ok "v1 的 $t 表已删除"
done

# 形状变了的表更要紧: ddl-auto=update 只加不删, 漏跑重建脚本的话旧列原样留着、新列一个都没有,
# 而失败会以运行期的 NOT NULL 插入错误出现 —— 排查成本远高于在这里红一下。
for c in installation_id principal_type principal_id companion_id user_id; do
  col_exists application_session "$c" && fail "application_session.$c 是 v1 的列, 该没了" \
                                      || ok "application_session.$c 已删除"
done
for c in owner_principal_type owner_principal_id visibility join_policy \
         min_participants max_participants; do
  col_exists application_session "$c" && ok "application_session.$c 存在" \
                                      || fail "缺 application_session.$c"
done

# 幂等作用域里那一列必须 NOT NULL。可空的话唯一键就形同虚设 —— PostgreSQL 的唯一索引对 NULL
# 不设防, 一列可空等于这个键随时可能退化成"不约束"(这一条是被一次真实的退化逼出来的:
# 库里曾留下 20 行 session_id 为 NULL 的调用记录, 而唯一约束一声不吭)。
NULLABLE=$(col_nullable action_invocation session_id)
[ "$NULLABLE" = "NO" ] && ok "action_invocation.session_id NOT NULL" \
  || fail "action_invocation.session_id 当前是 '$NULLABLE' — 唯一键会退化成不约束"

# 而 resource.session_id 反过来, 必须保持可空: 主体型资源(读的时候由投影器现算的那一类)
# 不属于任何会话, 这正是会话解析第 4/5 档存在的理由。
NULLABLE=$(col_nullable resource session_id)
[ "$NULLABLE" = "YES" ] && ok "resource.session_id 仍可空 (主体型资源不挂会话)" \
  || fail "resource.session_id 变成了 NOT NULL — 主体型资源将无处安放"

# 旧表这一半在 R8 打开 —— 到此为止 /api/v10 与 reminders 的读路径已全部下线, 表本身该由
# scripts/lap-drop-legacy.sh 清掉。留着它不会报错, 只会让下一个读到这张表的人以为它还是事实。
for t in dh_application dh_game_session dh_application_action_log reminders; do
  table_exists "$t" && fail "遗留表 $t 还在 — 跑 scripts/lap-drop-legacy.sh --apply" \
                     || ok "遗留表 $t 已删除"
done

# ── 登录 ──
# 位置在断言 1b 之前, 而不是像别处那样"先查库再登录": 1b 里那一次 POST /install 必须带着身份。
# Spring Security 对**未认证的 POST** 在路由之前就回 403, 于是不带令牌的探针无论端点存不存在
# 都得到 403 —— 那样这条断言就退化成"403 就是没装入口", 而不是"404 才是没有这个路由"。
# 带上令牌之后它才是真判据: 端点还在的话会走到 handler 并回 200, 只有真的不存在才 404。
note "登录取令牌"
CHECK_USER="${CHECK_USER:-haojie.chen.njau@gmail.com}"
CHECK_PASS="${CHECK_PASS:-20040719chj}"
AUTH=$(curl -s -m 15 -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$CHECK_USER\",\"password\":\"$CHECK_PASS\"}" \
  | $PY -c "import sys,json;print(json.load(sys.stdin).get('token',''))" 2>/dev/null || echo "")
if [ -z "$AUTH" ]; then
  fail "登录失败, 无法继续 (账号: $CHECK_USER)"
  echo ""
  echo "❌ 验收未通过"
  exit 1
fi
ok "登录成功"

# ── 断言 1b: v2 的入口不变量 (原则 1 / 决定 2) ──
# 这两条守的是"整次重构只是改了个名字"与"真的换了模型"之间的差别, 所以它们查的不是行为,
# 而是**入口与词汇**: 一个还活着的安装入口, 一段还留着老错误码的代码。
note "断言 1b: 没有安装入口, 也没有 NOT_INSTALLED"
CODE=$(http POST "/api/v1/applications/$APP_ID/install" '{}')
[ "$CODE" = "404" ] && ok "POST /install → 404 (原则 1: Application 不需要安装)" \
                    || fail "安装入口还在 (状态码 $CODE)"

# 全库找人: 老权限模型的错误码必须一个都不剩。留下一个永远发不出来的码, 只会让下一个读到它的
# 人以为安装还在 —— 断言"它发不出来"是测不出来的(缺了才是它该有的样子), 只能直接查源码。
# 只扫 Java 源码: 本脚本自己会提到这个名字, 文档也会, 而它们都不是"还能发出来的代码"。
#
# 这条抓到过一次真的: ParticipantService 的 javadoc 里还写着 INSTALLATION_INACTIVE, 而那段
# 注释同时还在说"WAITING 是进不去的状态" —— 那是修 syncStatus 之前的事实。一个词被抓出来,
# 牵出的是整段与代码相反的说明。注释不参与编译, 所以只有这种直接查字的断言拦得住它。
# 结尾那个 `|| true` 不是装饰: 脚本开着 `set -o pipefail`, 而这条 grep **一个都不匹配时退出码
# 是 1** —— 于是"老错误码已经清干净了"这件好事会把整个脚本在第 3 条断言上终止, 后面一条都不跑。
# 判据本来就是匹配**数**, 不是匹配成败, 所以这里要把退出码和计数分开。
STALE=$(grep -rn "NOT_INSTALLED\|INSTALLATION_INACTIVE" "$ROOT/backend" --include='*.java' 2>/dev/null | wc -l || true)
[ "$STALE" = "0" ] && ok "Java 源码里没有 NOT_INSTALLED / INSTALLATION_INACTIVE" \
                   || fail "还有 $STALE 处引用老权限模型的错误码: $(grep -rln 'NOT_INSTALLED\|INSTALLATION_INACTIVE' "$ROOT/backend" --include='*.java' | head -3 | tr '\n' ' ')"

# ── MCP 客户端(AGENT 身份) ──
# 定义在这个位置而不是断言 14 那里: 断言 9b 也要用它 —— "另一个 principal"在 HTTP 面上拿不到
# JWT(那一面只认真人), 而 MCP 恰好提供了一个真实存在的第二条入口。这不是为了省事: 拿它来演
# 外来者, 顺带把原则 6/10 证明了 —— REST 与 MCP 进的是同一个网关, 于是"不在场"这条拒绝在
# 两条传输上是同一个码, 而不是两套各自为政的判定。
#
# LAP_MCP_SERVICE_KEY 不是可选的礼貌参数: 服务端那把密钥留空 = MCP 完全关闭(每个请求 403),
# 而一个关闭的 MCP 与一个工作的 MCP 在"没有断言"这件事上长得一模一样 —— 所以下面遇到
# 需要 MCP 的断言时是**报错**而不是跳过。
MCP_KEY="${LAP_MCP_SERVICE_KEY:-}"
MCP_AGENT="check-lap-agent-$(stamp)"

# MCP 请求助手: 与 http() 同形, 但带头(而且不是 Authorization —— MCP 没有 JWT)。
mcp() {
  local payload="$1" key="${2:-}"
  local args=(-s -m 20 -o "$TMP/body" -D "$TMP/hdr" -w '%{http_code}' -X POST "$BASE/mcp"
    -H 'Content-Type: application/json'
    -H "X-Mcp-Principal: AGENT:$MCP_AGENT"
    -H "X-Mcp-Service-Key: $MCP_KEY"
    -d "$payload")
  if [ -n "$key" ]; then args+=(-H "Idempotency-Key: $key"); fi
  curl "${args[@]}" || echo "000"
}

# 给某个 AGENT 在某个会话里铺一个参与者行 + 一份授权(照抄同会话里真人那一份)。
#
# v2 里"这个 Agent 能不能被唤醒 / 能不能动手"的答案就在这两张表里 —— 而且是**按会话算的**:
# v1 问的是"这个 Agent 装了游戏没有"(应用级), v2 问的是"它在这一局里吗"(会话级)。
# 变化不只是形容词: 一个 Agent 可以在 A 局里坐着、在 B 局里完全不存在, 而 v1 的 installation
# 表达不了这件事。
#
# 为什么必须从数据造: AGENT 在 HTTP 面上拿不到 JWT(那一面只认真人), 而邀请链路的 REST 面
# 要到 R10 才有。与 v1 时代造 installation 行同一个套路, 只是造的东西换了。
# 第三个参数是"照抄谁": 传了就把那个人的授权复制一份。
seat_agent() {
  local session_id="$1" agent_id="$2" from_principal="${3:-}"
  # 传 URI 是最容易犯的错: 它们在这一段里长得几乎一样, 而失败的样子是一句
  # "value too long for type character varying(36)" —— 那句话说不出"你传错了哪一个变量"。
  # session_id 列宽 36 正好是 UUID 的长度, 而 game://session/<uuid> 是 50。
  case "$session_id" in
    *"://"*) fail "seat_agent 第 1 个参数应当是会话 id, 收到的是 URI: $session_id"; return ;;
  esac
  exec_sql "insert into application_session_participant
              (id, session_id, principal_type, principal_id, role, status, permission_profile, joined_at, companion_id, user_id)
            values (gen_random_uuid()::text, '$session_id', 'AGENT', '$agent_id', 'MEMBER', 'ACTIVE', 'MEMBER',
                    now(), '$agent_id', '$agent_id')"
  if [ -n "$from_principal" ]; then
    exec_sql "insert into session_permission
                (id, participant_id, capability_id, action_id, permission_level, risk_ceiling, created_at)
              select gen_random_uuid()::text,
                     (select id from application_session_participant
                       where session_id='$session_id'
                         and principal_type='AGENT' and principal_id='$agent_id'),
                     g.capability_id, g.action_id, g.permission_level, g.risk_ceiling, now()
              from session_permission g
              where g.participant_id = (select id from application_session_participant
                                         where session_id='$session_id'
                                           and principal_type='HUMAN' and principal_id='$from_principal')"
  fi
}

# 把一个 Agent 从场上完全撤走 —— 脚本要能重复跑。
# 顺序不能反: 授权挂在参与者行上, 参与者行还被 action_invocation 引用着。
unseat_agent() {
  local agent_id="$1"
  exec_sql "delete from session_permission where participant_id in
              (select id from application_session_participant
                where principal_type='AGENT' and principal_id='$agent_id')" || true
  exec_sql "delete from action_invocation
            where principal_type='AGENT' and principal_id='$agent_id'" || true
  exec_sql "delete from application_session_participant
            where principal_type='AGENT' and principal_id='$agent_id'" || true
}
mcp_cleanup() { unseat_agent "$MCP_AGENT"; }

# ── 断言 2: 能力目录 ──
note "断言 2: GET /api/v1/capabilities"
CODE=$(http GET /api/v1/capabilities)
[ "$CODE" = "200" ] && ok "200" || fail "状态码 $CODE"
CAPS=$(body)
echo "$CAPS" | grep -q '"game.play"' && ok "⊇ game.play" || fail "缺 game.play"
echo "$CAPS" | grep -q '"reminder.manage"' && ok "⊇ reminder.manage" || fail "缺 reminder.manage"

# ── 断言 3: 能力下的候选应用 ──
note "断言 3: GET /api/v1/capabilities/game.play/applications"
CODE=$(http GET /api/v1/capabilities/game.play/applications)
[ "$CODE" = "200" ] && ok "200" || fail "状态码 $CODE"
COUNT=$(jq_ "len(d)")
if [ "$COUNT" -ge 2 ]; then
  ok "候选应用 $COUNT 个 (≥2)"
else
  fail "候选应用 $COUNT 个 — 少于 2(井字棋 + 五子棋)"
fi
body | grep -q "$APP_ID" && ok "含 $APP_ID" || fail "缺 $APP_ID"
# 同 capability 的两个应用靠各自的 URI scheme 消歧 —— 它们的 action id 是<em>完全一样</em>的,
# 所以"发现到了两个"这件事必须真的落在数据上, 不能只看数量。
body | grep -q 'com.luxera.gomoku' && ok "含 com.luxera.gomoku" || fail "缺 com.luxera.gomoku"

# ── 断言 4: 动作发现与 manifest 一致 ──
note "断言 4: GET /api/v1/applications/$APP_ID/actions"
CODE=$(http GET "/api/v1/applications/$APP_ID/actions")
[ "$CODE" = "200" ] && ok "200" || fail "状态码 $CODE"
DECLARED=$(jq_ "','.join(sorted(a['actionId'] for a in d))")
[ "$DECLARED" = "game.create,game.make_move,game.state,game.surrender" ] \
  && ok "动作集合与 manifest 一致: $DECLARED" || fail "动作集合不符: '$DECLARED'"
body | grep -q '"agentHint"' && ok "agentHint 随发现一并返回" || fail "发现结果里没有 agentHint"

# ── 打开应用 = 开一个会话 ──
# v2 里"打开"产生的唯一东西就是一个会话, 而会话 id 就是后面每个动作 target 里那一段。
#
# 与 v1 的安装有一处必须说清的不同: **它不幂等**。v1 的 install 对同一个 principal 返回同一个
# installation(顺带开新会话), 所以脚本可以"重装一次拿个干净起点"; v2 没有安装这个概念,
# 每次调用都是新的一局。这恰恰是重构想要的 —— 想续上一局的人该拿旧的 sessionId 回来,
# 而不是指望"打开"这个动作返回同一个东西。
#
# $CAPABILITIES 是**这一局里我这个参与者**的授权摘要(不是应用声明的全部能力): 它由 join 时的
# role → permission_profile 展开而来, 断言 9a 正是把它清空之后看动作会不会被拒。
SESSION_ID=""; URI=""; PRINCIPAL_ID=""; CAPABILITIES=""; SESSION_VERSION=""
open_session() {
  local code
  code=$(http POST "/api/v1/applications/$APP_ID/sessions" '{}')
  if [ "$code" != "200" ]; then fail "打开应用状态码 $code"; return; fi
  # R11 起响应是 §16 的形状: application 与 participant 各自嵌套。老的两个平铺字段
  # (ownerPrincipal*) 已经不在响应里了 —— 主人只是 role=OWNER 的那一个参与者。
  SESSION_ID=$(jq_ "d['sessionId']")
  PRINCIPAL_ID=$(jq_ "d['participant']['principalId']")
  SESSION_VERSION=$(jq_ "d['application']['version']")
  CAPABILITIES=$(jq_ "','.join(sorted(d['capabilities']))")
  URI="game://session/$SESSION_ID"
  ok "已打开 → session=$SESSION_ID (app=$APP_ID v$SESSION_VERSION, owner=$PRINCIPAL_ID, 授权: $CAPABILITIES)"
}

note "打开应用开一局 (动作的 target 从这里来)"
open_session
[ -n "$SESSION_ID" ] && ok "target = $URI" || fail "开会话响应里没有 sessionId"

# ── 断言 18: §16 的响应形状 + 应用详情 + §4.1 可用性 (R11) ──
note "断言 18: 开会话返回 §16 形状, 应用详情给出 status/availability/ui"
[ "$SESSION_VERSION" = "1.0.0" ] && ok "application.version = $SESSION_VERSION" \
  || fail "application.version 不对: '$SESSION_VERSION'"
ROLE=$(jq_ "d['participant']['role']" 2>/dev/null || echo "")
# 上面那次 open_session 只留了变量, 这里再开一局只为读形状(每次调用都是新的一局, 见脚本上文)
code=$(http POST "/api/v1/applications/$APP_ID/sessions" '{}')
[ "$(jq_ "d['participant']['role']")" = "OWNER" ] && ok "participant.role = OWNER" \
  || fail "participant.role 不是 OWNER"

note "断言 18b: GET /api/v1/applications/$APP_ID (应用详情)"
CODE=$(http GET "/api/v1/applications/$APP_ID")
[ "$CODE" = "200" ] && ok "200" || fail "状态码 $CODE"
[ "$(jq_ "d['status']")" = "PUBLISHED" ] && ok "十态原值 status=PUBLISHED" \
  || fail "status = $(jq_ "d['status']")"
[ "$(jq_ "str(d['availability']['inMarket']).lower()")" = "true" ] && ok "availability.inMarket=true" \
  || fail "inMarket 不是 true"
[ "$(jq_ "str(d['availability']['allowsNewSession']).lower()")" = "true" ] && ok "allowsNewSession=true" \
  || fail "allowsNewSession 不是 true"
[ "$(jq_ "str(d['availability']['allowsExistingSession']).lower()")" = "true" ] && ok "allowsExistingSession=true" \
  || fail "allowsExistingSession 不是 true"
SURFACES=$(jq_ "len(d['ui']['surfaces'])")
[ "$SURFACES" = "5" ] && ok "ui.surfaces 五态全在" || fail "ui.surfaces = $SURFACES, 期望 5"
[ "$(jq_ "d['ui']['type']")" = "EMBEDDED" ] && ok "ui.type=EMBEDDED" || fail "ui.type 不对"

note "断言 18c: 没注册过的应用详情是 404 UNKNOWN_APPLICATION"
CODE=$(http GET "/api/v1/applications/com.luxera.nope")
[ "$CODE" = "404" ] && ok "404" || fail "状态码 $CODE"
body | grep -q "UNKNOWN_APPLICATION" && ok "错误码 UNKNOWN_APPLICATION" || fail "错误码不对"

# ── 断言 5: 幂等重放 ──
note "断言 5: 同 Idempotency-Key 两次 → 同响应 + Idempotent-Replay + 只有一行 invocation"
KEY="check-lap-create-$(stamp)"
CODE=$(http POST /api/v1/actions:execute \
  "{\"action\":\"game.create\",\"target\":\"$URI\",\"input\":{}}" "$KEY")
[ "$CODE" = "200" ] && ok "首次 200" || fail "首次状态码 $CODE"
cp "$TMP/body" "$TMP/first"

CODE=$(http POST /api/v1/actions:execute \
  "{\"action\":\"game.create\",\"target\":\"$URI\",\"input\":{}}" "$KEY")
[ "$CODE" = "200" ] && ok "重放 200" || fail "重放状态码 $CODE"
if diff -q "$TMP/first" "$TMP/body" >/dev/null; then
  ok "两次响应逐字节相同"
else
  fail "两次响应不同: $(diff "$TMP/first" "$TMP/body" | head -3 | tr '\n' ' ')"
fi
grep -qi '^Idempotent-Replay: true' "$TMP/hdr" && ok "带 Idempotent-Replay: true" || fail "缺 Idempotent-Replay 头"
ROWS=$(sql "select count(*) from action_invocation where idempotency_key='$KEY'")
[ "$ROWS" = "1" ] && ok "action_invocation 只有 1 行" || fail "action_invocation 有 $ROWS 行"

# ── 断言 6: 同 key 不同载荷 ──
note "断言 6: 同 key + 不同 body → 422 IDEMPOTENCY_KEY_REUSED"
CODE=$(http POST /api/v1/actions:execute \
  "{\"action\":\"game.create\",\"target\":\"$URI\",\"input\":{\"opponentPrincipalId\":\"nobody\"}}" "$KEY")
[ "$CODE" = "422" ] && ok "422" || fail "状态码 $CODE"
GOT=$(jq_ "d['error']['code']")
[ "$GOT" = "IDEMPOTENCY_KEY_REUSED" ] && ok "code=IDEMPOTENCY_KEY_REUSED" || fail "code=$GOT"

# ── 断言 7: 写动作缺幂等键 ──
note "断言 7: game.make_move 不带 Idempotency-Key → 400 IDEMPOTENCY_KEY_REQUIRED"
CODE=$(http POST /api/v1/actions:execute \
  "{\"action\":\"game.make_move\",\"target\":\"$URI\",\"input\":{\"position\":0}}")
[ "$CODE" = "400" ] && ok "400" || fail "状态码 $CODE"
GOT=$(jq_ "d['error']['code']")
[ "$GOT" = "IDEMPOTENCY_KEY_REQUIRED" ] && ok "code=IDEMPOTENCY_KEY_REQUIRED" || fail "code=$GOT"

# ── 断言 8: READ 不重放 ──
note "断言 8: 资源读没有幂等键, 落子前后各读一次 —— 第二次必须看到新棋盘"
RURI=$(encoded "$URI")

CODE=$(http GET "/api/v1/resources?uri=$RURI")
[ "$CODE" = "200" ] && ok "落子前读得到资源" || fail "读资源状态码 $CODE"
BEFORE=$(jq_ "sum(1 for c in d[0]['state']['board'] if c)")
[ "$BEFORE" = "0" ] && ok "落子前棋盘是空的" || fail "落子前棋盘已有 $BEFORE 个子"

CODE=$(http POST /api/v1/actions:execute \
  "{\"action\":\"game.make_move\",\"target\":\"$URI\",\"input\":{\"position\":4}}" \
  "check-lap-move-$(stamp)")
[ "$CODE" = "200" ] && ok "落子 200" || fail "落子状态码 $CODE"
AFTER=$(jq_ "d['resource']['state']['board'][4]")
[ "$AFTER" = "X" ] && ok "落子响应里 board[4]=X" || fail "落子后 board[4]='$AFTER'"

CODE=$(http GET "/api/v1/resources?uri=$RURI")
REREAD=$(jq_ "d[0]['state']['board'][4]")
[ "$REREAD" = "X" ] && ok "再读一次仍是 X" \
  || fail "重读得到 '$REREAD' —— READ 被幂等层重放成了开局时的空棋盘"

# ── 断言 9: 权限 —— 三段拒绝, 一段一条判据 ──
#
# v1 这三段问的是"装了没有"; v2 问的都是"在不在这一局里", 而"在"这个字被拆成两种情形,
# 所以还是三条 —— 但它们测的东西换了:
#
#   9a 在场但没有这个授权  → NOT_AUTHORIZED           (第二维: 参与者行有了, session_permission 没有)
#   9b 根本不在这一局里    → NOT_A_PARTICIPANT        (第一维, 取代 NOT_INSTALLED)
#   9c 曾经在场, 已经退场  → PARTICIPANT_INACTIVE     (取代 INSTALLATION_INACTIVE)
#
# 为什么 9b 值得单独占一条, 而不是被 9a "顺带覆盖": 两者的拒绝点相隔好几步代码。一个只测 9a 的
# 脚本在"参与者检查整个被删掉"时照样全绿(那时 9a 仍然会因为没授权而 403), 而 agent 会因此在
# 任何一局棋里都动得了手。这两条必须各自红。
note "断言 9a: 摘掉 session_permission → 403 NOT_AUTHORIZED"
# 摘掉这一份授权而不是删参与者: HTTP 上没有"只进这一局但不授权"的入口(join 会把 role 展开成
# 完整授权), 所以这个中间状态只能从数据造 —— 与 v1 时代摘 permission_grant 同一个套路。
exec_sql "delete from session_permission where participant_id =
          (select id from application_session_participant
            where session_id='$SESSION_ID' and principal_type='HUMAN' and principal_id='$PRINCIPAL_ID')"
CODE=$(http POST /api/v1/actions:execute \
  "{\"action\":\"game.make_move\",\"target\":\"$URI\",\"input\":{\"position\":0}}" \
  "check-lap-na-$(stamp)")
[ "$CODE" = "403" ] && ok "未授权 → 403" || fail "未授权状态码 $CODE"
GOT=$(jq_ "d['error']['code']")
[ "$GOT" = "NOT_AUTHORIZED" ] && ok "code=NOT_AUTHORIZED" || fail "code=$GOT"

# 重开一局: 新会话把授权补齐(join 时按 role 展开), 后面的断言要的是一个干净起点。
open_session

note "断言 9b: 不在这一局里的 AGENT → 403 NOT_A_PARTICIPANT"
# 这一段同时是原则 6/10 的端到端证明: 拒绝来自**同一个网关**。MCP 客户端没有 JWT, 它走的是
# /mcp 那条传输, 而它得到的是与 REST 面逐字相同的错误码 —— 两条入口后面不是两套判定。
if [ -z "$MCP_KEY" ]; then
  fail "LAP_MCP_SERVICE_KEY 未提供 —— 断言 9b 无法验证第二条传输上的同一个网关"
else
  # 这个 AGENT 在这一局里没有任何行(断言 14 才给它铺座位), 而且它挑的是一个真人开出来的会话。
  unseat_agent "$MCP_AGENT"
  CODE=$(mcp "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"tictactoe.game_make_move\",\"arguments\":{\"target\":\"$URI\",\"position\":3}}}" \
    "check-lap-np-$(stamp)")
  [ "$CODE" = "200" ] && ok "MCP 请求本身 200 (失败装在 result 里, 不是 HTTP 码)" \
                      || fail "MCP 状态码 $CODE"
  ISERR=$(jq_ "d['result']['isError']")
  GOT=$(jq_ "d['result']['structuredContent']['error']['code']")
  if [ "$ISERR" = "True" ] && [ "$GOT" = "NOT_A_PARTICIPANT" ]; then
    ok "code=NOT_A_PARTICIPANT (MCP 与 REST 进的是同一个网关)"
  else
    fail "不在场却没被拒: isError=$ISERR code=$GOT"
  fi
fi

note "断言 9c: 退场之后 → 403 PARTICIPANT_INACTIVE"
# 注意码是 PARTICIPANT_INACTIVE 而不是 NOT_A_PARTICIPANT —— 参与者那一行还在(状态 LEFT),
# 这两个状态对调用方的含义不同(一个该被重新邀请进来, 一个该走加入流程)。
CODE=$(http DELETE "/api/v1/sessions/$SESSION_ID/participants/me")
[ "$CODE" = "204" ] && ok "退场 204" || fail "退场状态码 $CODE"
CODE=$(http POST /api/v1/actions:execute \
  "{\"action\":\"game.make_move\",\"target\":\"$URI\",\"input\":{\"position\":0}}" \
  "check-lap-ui-$(stamp)")
[ "$CODE" = "403" ] && ok "退场后 → 403" || fail "退场后状态码 $CODE"
GOT=$(jq_ "d['error']['code']")
[ "$GOT" = "PARTICIPANT_INACTIVE" ] && ok "code=PARTICIPANT_INACTIVE" || fail "code=$GOT"
open_session   # 复原, 让后面的断言有干净的起点

# ── 断言 10: 跨能力域 —— 一个没有 {sessionId} 段的 target 也能落地 ──
# 这条断言的**重点已经不是"装了没有"**(那件事没有了), 而是会话解析的第 4/5 档:
# reminder://owner/{userId} 的 URI 里没有会话段, 前 3 档一个都匹配不上, 于是平台必须
# 用"这个 principal 在提醒应用下最近的活跃会话"兜底, 没有就给他开一个。
#
# 为什么这是 v2 最容易静默退化的地方: 少了第 4/5 档, 这次调用会以 UNKNOWN_SESSION 失败 ——
# 而它失败的样子与"提醒功能坏了"长得一模一样。所以这里断言的不只是 200, 还有**机器自己
# 开了/找到了一个会话**(下面那句 SQL)。
note "断言 10: 同一个 execute 端点把提醒建出来并读回收件箱 (会话由平台自己解出来)"
RURI="reminder://owner/$PRINCIPAL_ID"
BEFORE_SESSIONS=$(sql "select count(*) from application_session where application_id='com.luxera.reminder'")

CODE=$(http POST /api/v1/actions:execute \
  "{\"action\":\"reminder.create\",\"target\":\"$RURI\",\"input\":{\"type\":\"user_set\",\"title\":\"验收提醒\",\"dueAt\":\"2026-09-12T15:00\"}}" \
  "check-lap-rc-$(stamp)")
[ "$CODE" = "200" ] && ok "reminder.create 200" || fail "创建状态码 $CODE"
RID=$(jq_ "d['result']['id']")
[ -n "$RID" ] && ok "拿到提醒 id=$RID" || fail "响应里没有 result.id"

CODE=$(http GET "/api/v1/resources?uri=$(encoded "$RURI")")
[ "$CODE" = "200" ] && ok "读回收件箱 200" || fail "读收件箱状态码 $CODE"
# 读路径走的是 ResourceProjector(提醒状态在 reminder_item 表里, 不在 resource 表里) ——
# resource 表里没有这一行, 所以这一次 200 就是 "APP_OWNED backing 真的能用" 的端到端证明。
FOUND=$(jq_ "sum(1 for i in d[0]['state']['items'] if i['id']=='$RID')")
[ "$FOUND" = "1" ] && ok "刚建的提醒出现在同一个 URI 读出来的 items 里" \
  || fail "收件箱里找不到 $RID"

# 会话解析第 4/5 档真的动过: 提醒应用下的会话没变多(第 4 档命中已有会话)或正好多一个(第 5 档
# 现开一个)。两种都算对 —— 判据是"它没有掉到第 5 档的循环里", 也就是**每次调用都新建一个**。
AFTER_SESSIONS=$(sql "select count(*) from application_session where application_id='com.luxera.reminder'")
DELTA=$((AFTER_SESSIONS - BEFORE_SESSIONS))
if [ "$DELTA" -le 1 ]; then
  ok "提醒会话数 $BEFORE_SESSIONS → $AFTER_SESSIONS (没有每次调用都新建)"
else
  fail "提醒会话数涨了 $DELTA 个 —— 会话解析掉进了'每次新建'那一档"
fi

# 收尾: 清掉这次(以及重跑累计)为**这个 principal** 开的提醒会话, 让世界回到"他在提醒应用里
# 没有实例"的状态。与 v1 时代的卸载是同一个位置上的动作, 只是现在没有安装可卸 —— 会话就是
# 全部的关系。只清他自己开的: 数字人的提醒会话是 check.sh 那一条链路建的, 断言碰它就会把
# 别的脚本的状态搅乱(这条注释是被一次"顺手全删"写的)。
MINE="select id from application_session
       where application_id='com.luxera.reminder'
         and owner_principal_id='$PRINCIPAL_ID'"
exec_sql "delete from application_session_participant where session_id in ($MINE)"
exec_sql "delete from application_session where id in ($MINE)"

# ── 断言 11: 数字人链路 ──
# 这条断言的判据随服务的 LLM 而不同, 但**两种模式下都断言**:
#   默认(服务跑 mock LLM, 见 application.yml 的 mock-fallback): 断言"保险丝通着电"——
#     事件确实走完了 应用 → 平台 → 数字人 整条链, 数字人确实读了资源、确实看见有动作可做,
#     然后**故意**不动手, 并在日志里留下那句话。只断言"棋盘上没有多出 O"是不够的 ——
#     事件从没送到也能满足它。日志是唯一能区分"故意不动手"与"链路没通"的东西。
#   真实 LLM(LAP_EXPECT_AGENT_MOVE=1): 断言完整往返 —— 应手落在同一行 resource 上, 且
#     llm_calls 留下这次动作选择的记录。
# 两种模式都需要日志文件: 服务是 nohup java -jar ... > <log> 起的, 默认取 /tmp/companion-run.log。
DH_AGENT="check-lap-dh-$(stamp)"
DH_CREATE_KEY="check-lap-dh-create-$(stamp)"
DH_MOVE_KEY="check-lap-dh-move-$(stamp)"
DH_MODE=""

dh_cleanup() { unseat_agent "$DH_AGENT"; }
trap 'dh_cleanup; rm -rf "$TMP"' EXIT

note "断言 11: 真人走一步 → 数字人的应手 (R7)"
# G1 物理拆分: 数字人平台已迁往 simulation-agent-platform 独立进程。
# LAP_AGENT_MODE=split 时这一段诚实跳过 —— "事件到达数字人"从此要在两服务同起后由
# check-split.sh 断言(跨服务链路), 本脚本继续守平台侧的全部语义。
LAP_AGENT_MODE_ENV="${LAP_AGENT_MODE:-single}"
if [ "$LAP_AGENT_MODE_ENV" = "split" ]; then
  skip "数字人应手 — Agent 平台已独立进程, 跨服务链路由两服务同起后的 check-split.sh 断言"
  DH_MODE="skipped"
  # single 模式下断言 11 的末尾会另开一局把 $URI 复原成"没下过的棋"; split 模式跳过了
  # 那段, 这里补上同一动作, 让断言 14/15 拿到的还是它假设的那个起点。
  open_session
else
LAP_LOG="${LAP_LOG:-/tmp/companion-run.log}"
if [ ! -f "$LAP_LOG" ]; then
  fail "找不到服务日志 $LAP_LOG —— 没有它就无法区分'数字人故意不动手'与'事件根本没送到'。
        用 LAP_LOG=<服务 stdout 重定向到的文件> 再跑。"
else
  # 新开一局: 对手座位上坐着这位数字人 —— 应用因此会把"轮到你了"写进事件的 notifyPrincipalIds。
  # 这一步同时验证了那个分工: 应用只说"还有谁", 谁是数字人由平台查**参与者**决定。
  PREV_URI="$URI"
  open_session
  DH_URI="$URI"

  # 数字人得先**在这一局里**, 平台才认得出它是数字人 —— AgentRouteResolver 查的就是
  # application_session_participant, 而这是**会话级**的(v1 查的是应用级的 installation)。
  # 与断言 14 同一个理由: AGENT 在 HTTP 面上拿不到 JWT, 只能从数据造。
  #
  # 顺序要紧: 铺座位必须在**这一局的会话存在之后**。铺在上一局里的话, 事件所属会话查不到它,
  # 于是日志里那句"数字人不行动"永远不出现, 而失败信息只会说"15 秒内没看到 AgentApplicationFlow
  # 处理这条事件" —— 看起来像事件链路断了, 实际是座位铺错了局。
  #
  # 第三个参数是真人 principal: seat_agent 把真人那份 session_permission 复制一份给数字人
  # (launch 只对 owner 展开 grant, 没经 join 的 SQL 插入不会自动产生授权)。
  # 第一个参数是**会话 id** 而不是 $DH_URI —— 这两个变量在这里只差一个前缀, 传错会得到一句
  # 与"会话 id 传错了"毫无关系的 SQL 报错, 所以 seat_agent 自己也拦了一道。
  seat_agent "$SESSION_ID" "$DH_AGENT" "$PRINCIPAL_ID"
  if [ "$DH_URI" = "$PREV_URI" ]; then
    fail "重新安装没有开出新会话 ($DH_URI) —— 这一局会带着前面几手棋, 断言 11 不成立"
  fi
  CODE=$(http POST /api/v1/actions:execute \
    "{\"action\":\"game.create\",\"target\":\"$DH_URI\",\"input\":{\"opponentPrincipalId\":\"$DH_AGENT\"}}" \
    "$DH_CREATE_KEY")
  [ "$CODE" = "200" ] && ok "开局 200, 对手座位 = $DH_AGENT" || fail "开局状态码 $CODE"

  LINES_BEFORE=$(wc -l < "$LAP_LOG")
  CODE=$(http POST /api/v1/actions:execute \
    "{\"action\":\"game.make_move\",\"target\":\"$DH_URI\",\"input\":{\"position\":0}}" "$DH_MOVE_KEY")
  [ "$CODE" = "200" ] && ok "真人落子 board[0] (X)" || fail "真人落子状态码 $CODE"

  # 数字人那条链跑在 mailbox 线程上, 给它最多 15 秒
  for _ in $(seq 1 30); do
    NEW=$(tail -n +"$((LINES_BEFORE + 1))" "$LAP_LOG")
    if echo "$NEW" | grep -qF "数字人不行动: resource=$DH_URI"; then DH_MODE="declined"; break; fi
    if echo "$NEW" | grep -qF "数字人执行动作: action=game.make_move, resource=$DH_URI"; then DH_MODE="moved"; break; fi
    sleep 0.5
  done

  case "$DH_MODE" in
    declined)
      ok "事件走完了整条链: 应用 → 平台 → 数字人; 数字人读了资源、看见有动作可做, 然后拒绝" ;;
    moved)
      ok "事件走完了整条链, 数字人执行了动作" ;;
    *)
      fail "15 秒内没有看到 AgentApplicationFlow 处理这条事件 —— 事件链路断了? 日志: $LAP_LOG" ;;
  esac

  if [ -n "${LAP_EXPECT_AGENT_MOVE:-}" ] && [ "$DH_MODE" != "moved" ]; then
    fail "LAP_EXPECT_AGENT_MOVE=1 但数字人没有行动 —— 服务跑的多半还是 mock LLM"
  fi

  CODE=$(http GET "/api/v1/resources?uri=$(encoded "$DH_URI")")
  [ "$CODE" = "200" ] && ok "真人读得到这一局" || fail "读资源状态码 $CODE"
  DH_BOARD=$(jq_ "' '.join(x or '.' for x in d[0]['state']['board'])")
  DH_O=$(jq_ "sum(1 for x in d[0]['state']['board'] if x=='O')")

  if [ "$DH_MODE" = "moved" ]; then
    [ "$DH_O" = "1" ] && ok "数字人的应手在同一行 resource 上: $DH_BOARD" \
      || fail "数字人执行了动作, 棋盘上却有 $DH_O 个 O: $DH_BOARD"
    # 落库的这一刻就是"契约接通了"的凭据: LlmCallService 在 companionId 为空时静默跳过,
    # 所以这一行存在 = AgentApplicationFlow 设了 metadata, 且用途路由没有把它弄丢。
    LLM_ROWS=$(sql "select count(*) from llm_calls where companion_id='$DH_AGENT' and task='application-action-selection'")
    [ "${LLM_ROWS:-0}" -ge 1 ] && ok "llm_calls 里有 $LLM_ROWS 行 application-action-selection" \
      || fail "llm_calls 里没有这次动作选择的记录 —— 契约没接通或调用没落库"
  else
    [ "$DH_O" = "0" ] && ok "数字人没有动手 (LLM 不可用 ⇒ 绝不降级到启发式): $DH_BOARD" \
      || fail "数字人在 mock LLM 下仍然落了子 —— 有人加了一条启发式兜底: $DH_BOARD"
  fi

  # 复原: 断言 11 把 $URI 换成了数字人那一局, 而后面(附加/14)假设 $URI 是一盘没下过的棋。
  # 再开一局拿一个新会话还给它们 —— 这一局留给断言 15 用。
  open_session
fi
fi  # LAP_AGENT_MODE=split 的 else 在这里闭合

# ── 断言 12: 现实账本 ──
# 账本条目是"数字人真的动了手"的产物, 所以它和断言 15 是同一个前提, 不是同一轮次的事。
if [ "$DH_MODE" = "moved" ] && [ -n "$DH_AGENT" ]; then
  ROWS=$(sql "select count(*) from timeline_event where person_id='$DH_AGENT' and event_type='APPLICATION_ACTION_EXECUTED'")
  [ "${ROWS:-0}" -ge 1 ] && ok "现实账本(timeline_event)里有一条 APPLICATION_ACTION_EXECUTED" \
    || fail "数字人落了子, 账本里却没有这条经历 (rows=$ROWS)"
else
  skip "reality ledger 新增 APPLICATION_ACTION_EXECUTED — 需要真实 LLM (当前 $DH_MODE)"
fi

# ── 断言 13: /api/v10 已下线 ──
note "断言 13: GET /api/v10/applications → 404"
CODE=$(http GET /api/v10/applications)
[ "$CODE" = "404" ] && ok "404" || fail "状态码 $CODE (旧应用面还在服役)"

# ── 附加: Canonical / Alias 两条路由 ──
note "附加: /actions:execute 与 /actions/execute 是同一个 handler"
CODE=$(http POST /api/v1/actions/execute \
  "{\"action\":\"game.teleport\",\"target\":\"$URI\",\"input\":{}}")
[ "$CODE" = "404" ] && ok "别名路由可用 (404)" || fail "别名路由状态码 $CODE"
GOT=$(jq_ "d['error']['code']")
[ "$GOT" = "ACTION_NOT_FOUND" ] && ok "code=ACTION_NOT_FOUND" || fail "code=$GOT"

# ── 断言 14: MCP 适配器 ──
# MCP 只是一个适配器: tools/list 走动作发现, tools/call 走 ActionGateway.execute —— 与真人
# **同一条**路。所以这一段的判据不是"MCP 返回了 200", 而是"真人从 REST 读同一个 URI 时,
# 看见 MCP 客户端刚下的那一手"。前者在适配器自己伪造响应时也会绿。
#
# 两条前提, 缺一条这一段就什么也测不到:
#   1. **服务必须带 LAP_MCP_SERVICE_KEY 启动**(见 McpPrincipalResolver: 密钥留空 = MCP 完全关闭,
#      不是"无鉴权")。脚本自己没有这个密钥就没法鉴权, 那是配置缺失, 报错而不是跳过。
#   2. v2 里 AGENT 要动手, 得**在那盘棋里**(一张 application_session_participant 行 + 一份
#      session_permission), 而不是"装了那个应用"。HTTP 面上够不到这个状态(AGENT 拿不到 JWT),
#      所以只能从数据造 —— 与断言 9b/11 同一套路。
note "断言 14: POST /mcp —— tools/list 给目录, tools/call 改的是真人读的同一个 resource"
if [ -z "$MCP_KEY" ]; then
  fail "LAP_MCP_SERVICE_KEY 未提供 —— 脚本无法以 AGENT 身份调用 MCP, 断言 14 无法进行"
else
  # 先清后建, 让脚本可以重复跑
  mcp_cleanup

  CODE=$(mcp '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}')
  [ "$CODE" = "200" ] && ok "initialize 200" || fail "initialize 状态码 $CODE"
  grep -qi '^Mcp-Session-Id:' "$TMP/hdr" && ok "回了 Mcp-Session-Id (协议会话, 不落库)" \
    || fail "缺 Mcp-Session-Id 响应头"
  PROTO=$(jq_ "d['result']['protocolVersion']")
  [ "$PROTO" = "2025-06-18" ] && ok "协商到的协议版本 $PROTO" || fail "协议版本 '$PROTO'"

  CODE=$(mcp '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')
  [ "$CODE" = "200" ] && ok "tools/list 200" || fail "tools/list 状态码 $CODE"
  body | grep -q 'tictactoe.game_make_move' && ok "工具名含 tictactoe.game_make_move" \
    || fail "目录里没有 tictactoe.game_make_move"
  body | grep -q 'gomoku.game_make_move' && ok "工具名含 gomoku.game_make_move (同名动作不撞车)" \
    || fail "目录里没有 gomoku.game_make_move"
  body | grep -q 'game://session/{sessionId}' && ok "工具描述里写明了 target 的形态" \
    || fail "工具描述里没有 target 形态 —— 客户端无从知道该填什么"
  ok "共 $(jq_ "len(d['result']['tools'])") 个工具"

  # 真人开一局、落一子(X) —— 这盘棋的 sessionId 就是 MCP 客户端待会儿要进的会话
  CODE=$(http POST /api/v1/actions:execute \
    "{\"action\":\"game.create\",\"target\":\"$URI\",\"input\":{}}" "check-lap-mcp-c-$(stamp)")
  [ "$CODE" = "200" ] && ok "真人 game.create 200" || fail "真人 create 状态码 $CODE"
  CODE=$(http POST /api/v1/actions:execute \
    "{\"action\":\"game.make_move\",\"target\":\"$URI\",\"input\":{\"position\":0}}" "check-lap-mcp-h-$(stamp)")
  [ "$CODE" = "200" ] && ok "真人落子 0 200" || fail "真人落子状态码 $CODE"

  # 把 MCP 客户端作为 O 拉进这一局 —— 复制真人那份授权。注意它进的是**这一局**(session_id=$SESSION_ID),
  # 不是"这个应用": 这正是 v2 与 v1 最直观的差别, 一个 Agent 可以在 A 局里、在 B 局外。
  seat_agent "$SESSION_ID" "$MCP_AGENT" "$PRINCIPAL_ID"
  GRANTS=$(sql "select count(*) from session_permission sp
                 join application_session_participant p on p.id=sp.participant_id
                 where p.principal_type='AGENT' and p.principal_id='$MCP_AGENT'")
  [ "${GRANTS:-0}" -ge 1 ] && ok "AGENT 进了这一局 + $GRANTS 条授权 (SQL 造)" || fail "AGENT 授权没造出来"

  SESSIONS_BEFORE=$(sql "select count(*) from application_session")

  # MCP 客户端应手: 同一个 resource URI, 另一个 principal, 另一条传输
  CODE=$(mcp "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"tictactoe.game_make_move\",\"arguments\":{\"target\":\"$URI\",\"position\":4}}}" \
    "check-lap-mcp-a-$(stamp)")
  [ "$CODE" = "200" ] && ok "MCP tools/call 200" || fail "tools/call 状态码 $CODE"
  ISERR=$(jq_ "d['result']['isError']")
  [ "$ISERR" = "False" ] && ok "isError=false" \
    || fail "动作被拒: isError=$ISERR code=$(jq_ "d['result']['structuredContent']['error']['code']")"
  MARK=$(jq_ "d['result']['structuredContent']['result']['board'][4]")
  [ "$MARK" = "O" ] && ok "Agent 作为 O 落在 board[4]" || fail "board[4]='$MARK' (期望 Agent 的 O)"

  # 真人这一侧: 同一个 URI, 同一个读路径
  CODE=$(http GET "/api/v1/resources?uri=$(encoded "$URI")")
  [ "$CODE" = "200" ] && ok "真人读同一个 URI 200" || fail "读资源状态码 $CODE"
  B0=$(jq_ "d[0]['state']['board'][0]"); B4=$(jq_ "d[0]['state']['board'][4]")
  if [ "$B0" = "X" ] && [ "$B4" = "O" ]; then
    ok "共享世界: 同一行 resource, board[0]=X(真人) / board[4]=O(Agent)"
  else
    fail "两边不是同一盘棋: board[0]='$B0' board[4]='$B4'"
  fi

  # MCP Session ≠ ApplicationSession —— 最要紧的那条不变量: MCP 协议会话只是传输层的信封,
  # 不该在归属链上留下任何一行。真人开的那一局是唯一新增的会话, 之后不应再涨。
  SESSIONS_AFTER=$(sql "select count(*) from application_session")
  [ "$SESSIONS_AFTER" = "$SESSIONS_BEFORE" ] && ok "整条 MCP 链路没有创建 ApplicationSession" \
    || fail "application_session 从 $SESSIONS_BEFORE 涨到 $SESSIONS_AFTER —— MCP 会话污染了归属链"

  mcp_cleanup
fi

# ── 断言 16: 生命周期状态机 + 发布后不可变 ──
# 这一段的判据不是"PATCH 返回了 200", 而是"被挂起的应用从发现链上<em>真的</em>消失了" ——
# 一个从不被读的状态字段与一个不存在的状态字段没有任何区别。
#
# 上下架是平台/开发者的事, 不是使用者的事, 所以这里的身份走服务密钥(APPLICATION), 不走 JWT。
# 最后那条"You can't do this as a human"是刻意加的: 真人拿 JWT 也能到达这个端点, 但必须被
# 403 挡下, 而不是被"反正他也会点对"放过去。
note "断言 16: 开发者 API —— 状态机与发布后不可变"
DEV_APP="com.luxera.gomoku"
dev() {
  local method="$1" url="$2" payload="${3:-}"
  local args=(-s -m 20 -o "$TMP/body" -D "$TMP/hdr" -w '%{http_code}' -X "$method" "$BASE$url"
    -H "X-Mcp-Principal: APPLICATION:check-lap-dev"
    -H "X-Mcp-Service-Key: $MCP_KEY"
    -H "X-Correlation-Id: check-lap-dev-$(stamp)")
  if [ -n "$payload" ]; then args+=(-H 'Content-Type: application/json' -d "$payload"); fi
  curl "${args[@]}" || echo "000"
}

if [ -z "$MCP_KEY" ]; then
  skip "开发者面 — 需要 LAP_MCP_SERVICE_KEY 才能以 APPLICATION 身份鉴权"
else
  CODE=$(dev PATCH "/api/v1/applications/$DEV_APP/status" '{"status":"SUSPENDED"}')
  [ "$CODE" = "200" ] && ok "PATCH → SUSPENDED 200" || fail "PATCH 状态码 $CODE"
  GOT=$(jq_ "d['status']")
  [ "$GOT" = "SUSPENDED" ] && ok "status=SUSPENDED (previous=$(jq_ "d['previous']"))" || fail "status=$GOT"

  CODE=$(http GET /api/v1/capabilities/game.play/applications)
  [ "$CODE" = "200" ] && ok "重读候选应用 200" || fail "状态码 $CODE"
  body | grep -q "$DEV_APP" && fail "已挂起的应用仍出现在候选里 — 状态字段没被任何人读" \
                           || ok "已挂起的应用从候选里消失"
  body | grep -q "$APP_ID" && ok "同能力的另一个应用不受影响" || fail "$APP_ID 也一起消失了"

  # 从 PUBLISHED 直接跳回 DEVELOPING 是不合法的 —— 那会让已发布版本重新可变。
  CODE=$(dev PATCH "/api/v1/applications/$DEV_APP/status" '{"status":"DEVELOPING"}')
  [ "$CODE" = "409" ] && ok "跳步 → 409" || fail "跳步状态码 $CODE"
  GOT=$(jq_ "d['error']['code']")
  [ "$GOT" = "ILLEGAL_TRANSITION" ] && ok "code=ILLEGAL_TRANSITION" || fail "code=$GOT"

  # 真人走 JWT 打同一个端点 —— 必须被挡下。
  CODE=$(http PATCH "/api/v1/applications/$DEV_APP/status" '{"status":"PUBLISHED"}')
  [ "$CODE" = "403" ] && ok "真人推状态机 → 403" || fail "真人状态码 $CODE"

  # 复原: 应用回到架上, 版本行也跟着回来(否则"恢复了但还没上架"这种状态会留在库里)。
  CODE=$(dev PATCH "/api/v1/applications/$DEV_APP/status" '{"status":"PUBLISHED"}')
  [ "$CODE" = "200" ] && ok "PATCH → PUBLISHED 200" || fail "恢复状态码 $CODE"
  CODE=$(http GET /api/v1/capabilities/game.play/applications)
  body | grep -q "$DEV_APP" && ok "恢复后重新出现在候选里" || fail "恢复之后仍然查不到"

  # 发布后不可变: 对<em>随二进制发出去的那一版</em>写 manifest 必须被拒。
  # 这里不造一个人为的已发布行 —— 真实的行是启动同步写的, 那才是要挡住的场景。
  # 载荷是半截 JSON 也无所谓: 状态检查排在解析之前, 所以要拿到的仍然是 VERSION_IMMUTABLE,
  # 而不是"你的 JSON 有问题"。这一条正是校验顺序的端到端体现。
  CODE=$(dev PUT "/api/v1/applications/$APP_ID/versions/1.0.0/manifest" \
    '{"identity":{"id":"com.luxera.tictactoe","name":"改过的","version":"1.0.0","description":"x","category":"game"}}')
  [ "$CODE" = "409" ] && ok "改写已发布版本的 manifest → 409" || fail "状态码 $CODE"
  GOT=$(jq_ "d['error']['code']")
  [ "$GOT" = "VERSION_IMMUTABLE" ] && ok "code=VERSION_IMMUTABLE" || fail "code=$GOT"
fi

# ── 断言 17: INBOX 订阅的持久投递 ──
# 订阅有两种出口: SINK 是"现在就送到", INBOX 是"我一定会送到"。后者靠 lap_outbox ——
# 事件先落成一行数据(与业务同一个事务), 再由 relay 重试到送达或判死。
#
# 判据刻意分成两步: 先"有一行", 再"这行被投出去了"。只断言第一步的话, 一个从不投递的
# relay 也能全绿; 只断言第二步的话, 一个不落库就直投的实现也能全绿 —— 而那样进程一死就丢。
note "断言 17: INBOX 订阅落进 lap_outbox 并被 relay 投出"
if [ "$LAP_AGENT_MODE_ENV" = "split" ]; then
  skip "INBOX 投递 — 事件消费者(数字人)已独立进程, G3 后 relay 投 HTTP, 到时由 check-split.sh 断言投递"
elif [ "$FAIL" != "0" ]; then
  skip "outbox 投递 — 前面的断言已经失败, 这一局的起点不可信"
else
  # 开一盘全新的棋: 前面几段都在同一局上落过子, 复用那个 URI 会让"这两手有没有真的
  # 产生事件"取决于前面跑成什么样。事件 id 里带 moves 计数, 所以两手之间不会互相去重。
  #
  # 新会话而不是自己编一个新 URI —— target 里那一段是**真的会话 id**, 网关会拿它去查会话,
  # 编一个 `game://session/outbox-<时间戳>` 得到的是 404 UNKNOWN_SESSION, 而那一手连同它
  # 本该发出的 game.move 事件根本不会发生(这行注释是照着一次真实的失败写的: 断言当时报的是
  # "lap_outbox 里没有这个订阅的行", 看起来像投递坏了, 实际是压根没有事件可投)。
  open_session
  SUB_URI="$URI"
  CODE=$(http POST /api/v1/actions:execute \
    "{\"action\":\"game.create\",\"target\":\"$SUB_URI\",\"input\":{}}" \
    "check-lap-ob-0-$(stamp)")
  [ "$CODE" = "200" ] && ok "新开一局: $SUB_URI" || fail "开局状态码 $CODE"

  CODE=$(http POST /api/v1/subscriptions \
    "{\"sessionId\":\"$SESSION_ID\",\"resourceUriPattern\":\"game://session/**\",\"eventTypes\":[\"game.move\"],\"deliveryMode\":\"INBOX\"}")
  [ "$CODE" = "200" ] && ok "建 INBOX 订阅 200" || fail "订阅状态码 $CODE"
  SUB_ID=$(jq_ "d['subscriptionId']")
  [ -n "$SUB_ID" ] && ok "拿到订阅 id=$SUB_ID" || fail "响应里没有 subscriptionId"

  http POST /api/v1/actions:execute \
    "{\"action\":\"game.make_move\",\"target\":\"$SUB_URI\",\"input\":{\"position\":0}}" \
    "check-lap-ob-1-$(stamp)" >/dev/null
  http POST /api/v1/actions:execute \
    "{\"action\":\"game.make_move\",\"target\":\"$SUB_URI\",\"input\":{\"position\":1}}" \
    "check-lap-ob-2-$(stamp)" >/dev/null

  ROWS=$(sql "select count(*) from lap_outbox where subscription_id='$SUB_ID'")
  [ "${ROWS:-0}" -ge 1 ] && ok "收件箱里落下 $ROWS 行" \
    || fail "lap_outbox 里没有这个订阅的行 — INBOX 还是只被记下来而已"

  # relay 默认每 5 秒一轮, 给 20 秒的余量。
  DELIVERED=0
  for _ in $(seq 1 20); do
    DELIVERED=$(sql "select count(*) from lap_outbox where subscription_id='$SUB_ID' and status='DELIVERED'")
    [ "${DELIVERED:-0}" -ge 1 ] && break
    sleep 1
  done
  [ "${DELIVERED:-0}" -ge 1 ] && ok "relay 在 20 秒内投出 $(printf '%s' "$DELIVERED") 行" \
    || fail "20 秒过去仍没有一行被投出 — 收件箱攒着但没人送"

  LASTA=$(sql "select (last_delivered_at is not null) from subscription where id='$SUB_ID'")
  [ "$LASTA" = "t" ] && ok "订阅自己的 last_delivered_at 跟上了" \
    || fail "last_delivered_at 仍为空 — 排障时无从知道这个订阅还活着没有"

  # 收尾: 撤掉订阅, 免得重跑时同一批事件被两条订阅各收一次。
  http DELETE "/api/v1/subscriptions/$SUB_ID" >/dev/null
fi

# ── 断言 19: 把应用带进一段对话 (R12 / §63–§66) ──
# 这一段的判据与别处不同: 前面所有断言走的都是 /api/v1(应用平台自己的面), 而这里走的是
# **聊天平台**的三个会话上下文端点。它们之所以必须存在, 是因为 chat-platform 在编译期
# 看不见 application-platform(§115), 只能隔着契约端口 ApplicationCatalogPort 说话 ——
# 于是"在一个真实进程里, 这个端口两侧真的接上了"只有端到端测得出来:
# `ChatApplicationPortTest` 用的是假实现, 它证明不了真的适配器。
#
# 还要证明的是 §66 那句话: 卡片**是一条消息**, 不是一张新表。判据是 messages 表里那一行,
# 以及它和同一段对话里别的消息排在同一条时间线上。
note "断言 19: 在对话里开应用 → 卡片消息 → 分享链接 (R12)"
if [ "$LAP_AGENT_MODE_ENV" = "split" ]; then
  skip "对话内应用集成 — 本段需要先建一个伴侣(/api/companions), 该端点随 Agent 平台独立; 两服务同起后再验"
elif [ "$FAIL" != "0" ]; then
  skip "对话内应用集成 — 前面的断言已经失败, 这一段的起点不可信"
else
  R12_PERSONA='{"name":"小满","description":"一个温柔独立的女生","traits":["温柔"]}'
  R12_CID=$(curl -s -m 20 -X POST "$BASE/api/companions" \
    -H "Authorization: Bearer $AUTH" -H 'Content-Type: application/json' \
    -d "{\"persona\": $R12_PERSONA, \"relationshipType\": \"best_friend\"}" \
    | $PY -c "import sys,json;print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
  R12_CONV=$(curl -s -m 20 -X POST "$BASE/api/companions/$R12_CID/conversations/first" \
    -H "Authorization: Bearer $AUTH" -H 'Content-Type: application/json' -d '{}' \
    | $PY -c "import sys,json;print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")

  if [ -z "$R12_CID" ] || [ -z "$R12_CONV" ]; then
    fail "建不出验收用的伴侣/对话 (companion='$R12_CID' conversation='$R12_CONV')"
  else
    R12_BASE="/api/companions/$R12_CID/conversations/$R12_CONV/applications"

    # 19a: 看 —— 这段对话里能开什么
    CODE=$(http GET "$R12_BASE")
    [ "$CODE" = "200" ] && ok "GET 会话上下文 200" || fail "状态码 $CODE"
    body | grep -q "$APP_ID" && ok "可开列表里有 $APP_ID" || fail "可开列表里没有 $APP_ID"

    # 19b: 开 —— 应用真的开起来了, 而且会话行上记住了它属于哪段对话 (§85)
    CODE=$(http POST "$R12_BASE" "{\"applicationId\":\"$APP_ID\"}")
    [ "$CODE" = "201" ] && ok "POST 开应用 201" || fail "状态码 $CODE"
    R12_SESSION=$(jq_ "d['session']['sessionId']")
    R12_MSG=$(jq_ "d['messageId']")
    [ "$(jq_ "d['participant']['role']")" = "OWNER" ] && ok "开的人是 OWNER" \
      || fail "role = $(jq_ "d['participant']['role']")"
    [ "$(jq_ "d['session']['conversationId']")" = "$R12_CONV" ] \
      && ok "会话记住了它属于哪段对话" || fail "session.conversationId 不是这段对话"
    # 端口另一侧那一列也要真的写上 —— 响应里对不算, 库里对才算。
    DB_CONV=$(sql "select conversation_id from application_session where id='$R12_SESSION'")
    [ "$DB_CONV" = "$R12_CONV" ] && ok "库里 application_session.conversation_id 一致" \
      || fail "库里是 '$DB_CONV'"

    # 19c: §66 —— 卡片是一条消息, 不是一张新表
    KIND=$(sql "select message_kind from messages where id='$R12_MSG'")
    [ "$KIND" = "APPLICATION_CARD" ] && ok "落下的是一条 APPLICATION_CARD 消息" \
      || fail "message_kind = '$KIND'"
    [ "$(sql "select sender_type from messages where id='$R12_MSG'")" = "system" ] \
      && ok "发送者是 system (平台通告, 不是谁说的话)" || fail "sender_type 不对"

    # 卡片和普通消息排在同一条时间线上 —— 没有第二套分页/排序/已读体系。
    R12_COUNT=$(sql "select count(*) from messages where conversation_id='$R12_CONV'")
    [ "${R12_COUNT:-0}" -ge 1 ] && ok "它在 $R12_CONV 的消息流里 (共 $R12_COUNT 条)" \
      || fail "会话里一条消息都没有"

    # 19d: 分享 —— 铸票 + 落一条带明文链接的消息; 而库里只有哈希
    CODE=$(http POST "$R12_BASE/$R12_SESSION/share" '{"role":"MEMBER","maxUses":3}')
    [ "$CODE" = "201" ] && ok "POST 分享 201" || fail "状态码 $CODE"
    R12_TOKEN=$(jq_ "d['token']")
    R12_INVITE_MSG=$(jq_ "d['messageId']")
    [ -n "$R12_TOKEN" ] && ok "铸造响应里有明文令牌 (只此一次)" || fail "响应里没有 token"
    [ "$(sql "select message_kind from messages where id='$R12_INVITE_MSG'")" = "APPLICATION_INVITATION" ] \
      && ok "分享也落成了一条消息" || fail "分享消息的 message_kind 不对"
    # 明文绝不进库 —— 拿到数据库的人不该能替别人加入。
    R12_LEAK=$(sql "select count(*) from session_invitation where token_hash='$R12_TOKEN'")
    [ "$R12_LEAK" = "0" ] && ok "明文令牌没有进库 (库里存的是哈希)" \
      || fail "明文令牌出现在 token_hash 列里"

    # 19e: 拒绝也要跨过端口说清楚 —— 一个不存在的应用
    # 这一条验的是跨模块的错误翻译: 应用平台抛 SessionException, 端口那侧换成
    # ApplicationCatalogException, 聊天侧再翻成 HTTP。三跳任何一跳断了, 客户端都会拿到 500。
    CODE=$(http POST "$R12_BASE" '{"applicationId":"com.luxera.nope"}')
    [ "$CODE" = "404" ] && ok "开一个不存在的应用 → 404 (错误跨过了模块边界)" \
      || fail "状态码 $CODE (期望 404, 拿到 500 说明错误翻译断了)"
    body | grep -q "UNKNOWN_APPLICATION" && ok "code=UNKNOWN_APPLICATION" \
      || fail "错误码不对: $(body)"

    # 19f: 别人开的应用不该从这个入口看出来 —— 会话上下文只回答"这段对话里的事"
    R12_OTHER=$(sql "select count(*) from application_session where conversation_id='$R12_CONV'")
    [ "$R12_OTHER" = "1" ] && ok "这段对话里只有刚开的那一局" || fail "会话数 = $R12_OTHER"

    # 清理。两半各有各的归属, 所以走两条不同的路:
    # 伴侣那一半交给 REST(它自己有一整套级联: 消息、会话、认知、事件), 手工删会漏表;
    # 应用会话那一半反过来 —— 它不挂在伴侣下面(§2: Application 与 Digital Human 互不相识),
    # 只能显式删。参与者与邀请票先走, 免得留下没有会话的孤儿行。
    curl -s -X DELETE "$BASE/api/companions/$R12_CID" -H "Authorization: Bearer $AUTH" \
      -o /dev/null 2>/dev/null || true
    exec_sql "delete from session_permission where participant_id in
                (select id from application_session_participant where session_id='$R12_SESSION')" \
      >/dev/null 2>&1 || true
    exec_sql "delete from application_session_participant where session_id='$R12_SESSION'" \
      >/dev/null 2>&1 || true
    exec_sql "delete from session_invitation where session_id='$R12_SESSION'" >/dev/null 2>&1 || true
    exec_sql "delete from application_session where conversation_id='$R12_CONV'" >/dev/null 2>&1 || true
    # REST 那次 DELETE 留下的是"软删"的伴侣与它的对话 —— 对产品是对的(用户要能恢复),
    # 对验收脚本不是: 每跑一次就往库里多堆两条卡片消息。所以这里把它清干净。
    exec_sql "delete from messages where conversation_id='$R12_CONV'" >/dev/null 2>&1 || true
    exec_sql "delete from conversations where companion_id='$R12_CID'" >/dev/null 2>&1 || true
    exec_sql "delete from companions where id='$R12_CID'" >/dev/null 2>&1 || true
  fi
fi

# ── 断言 20: 数字人参与 —— 一封定向邀请信能走多远 (R13 / §59–§60) ──
# 这条断言要证明的是 v2 与 v1 在"数字人怎么进来"上的分别。v1 的答案是**安装**: 数据库里有一行
# AGENT 的 installation, 于是事件路由找得到它、权限判定认得出它。v2 里没有安装, 数字人进一场
# 会话只有一条路 —— **被邀请**。
#
# 判据分两半, 两半都不能少:
#
#   1. **信送到了。** 定向票从铸票那一步开始要跨过四道边界: REST(应用平台) → LapEventPublisher
#      的平台事件通道 → DhApplicationEventSink(单向门) → 数字人的邮箱 → 邀请处理器。这条链在
#      "mvn test 全绿"里是看不出来的: 单测把端口换成假的, 它证明不了真的适配器接上了。
#      这里用日志判据, 与断言 11 同一个理由 —— 而**收货人此刻还不在这一场里**(下面先断言
#      参与者行是 0), 于是这条同时证明了邀请事件不走 AgentRouteResolver 的名单:
#      "问他在不在这场里"对一个被邀请的人来说答案永远是没有(§60 / 计划修正 2)。
#
#   2. **它自己决定。** 在 mock LLM 下这个决定必须是"没决定"(IGNORE), 账本里**一条都不许有** ——
#      不是"谢绝"。把一次服务抖动写成"这个数字人拒绝过谁", 是账本里最难查的一类假话; 反过来,
#      默认一个"去"会让它在 LLM 不可用时到处乱窜。两种默认值都在这里被挡住。真实 LLM 下
#      (LAP_EXPECT_AGENT_JOIN=1)则断言它真的进了场、账本里留下 ACCEPTED。
note "断言 20: 定向邀请 → 数字人自己决定去不去 (R13)"
if [ "$LAP_AGENT_MODE_ENV" = "split" ]; then
  skip "定向邀请的数字人侧 — Agent 平台已独立进程, 邀请消费链路由两服务同起后断言"
elif [ "$FAIL" != "0" ]; then
  skip "数字人参与 — 前面的断言已经失败, 这一段的起点不可信"
else
  R13_PERSONA='{"name":"小满","description":"一个温柔独立的女生","traits":["温柔"]}'
  R13_CID=$(curl -s -m 20 -X POST "$BASE/api/companions" \
    -H "Authorization: Bearer $AUTH" -H 'Content-Type: application/json' \
    -d "{\"persona\": $R13_PERSONA, \"relationshipType\": \"best_friend\"}" \
    | $PY -c "import sys,json;print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")

  if [ -z "$R13_CID" ]; then
    fail "建不出验收用的伴侣 —— 没有收件人就没有邀请"
  else
    # 这一局是这一段自己的, 所以不走 open_session(那会把 $SESSION_ID/$URI 换成别人以为还在的
    # 那一局, 而断言 15 之后还有人要用它们)。
    CODE=$(http POST "/api/v1/applications/$APP_ID/sessions" '{}')
    R13_SESSION=$(jq_ "d['sessionId']")
    [ "$CODE" = "200" ] && [ -n "$R13_SESSION" ] && ok "开了这一段的会话 $R13_SESSION" \
      || fail "开会话状态码 $CODE"

    # 铸票之前先钉住前提: **收件人还不在这一场里**。这句话是下面那条日志判据的分量所在 ——
    # 一个已经在场的数字人收到信是理所当然的, 一个不在场的人收到才是路由绕开了名单。
    R13_SEATED=$(sql "select count(*) from application_session_participant
                        where session_id='$R13_SESSION' and principal_type='AGENT'")
    [ "$R13_SEATED" = "0" ] && ok "收件人此刻还不在这一场里 (AGENT 参与者 0 行)" \
      || fail "还没邀请就已经有 $R13_SEATED 行 AGENT 参与者 —— 下面的判据不成立了"

    # 20a: 铸一张**定向**票 —— targetType/targetId 说的是"被邀请的那一位是谁";
    # 调用方自己的身份仍然只由 Authorization 决定(§36), 请求体里没有"我是谁"。
    LINES_BEFORE=$(wc -l < "$LAP_LOG")
    CODE=$(http POST "/api/v1/sessions/$R13_SESSION/invitations" \
      "{\"role\":\"MEMBER\",\"maxUses\":1,\"targetType\":\"AGENT\",\"targetId\":\"$R13_CID\"}")
    [ "$CODE" = "200" ] && ok "定向邀请 200" || fail "状态码 $CODE"
    R13_TOKEN=$(jq_ "d['token']")
    R13_INVITATION=$(jq_ "d['invitationId']")
    [ "$(jq_ "d['targetType']")" = "AGENT" ] && [ "$(jq_ "d['targetId']")" = "$R13_CID" ] \
      && ok "票上记着收件人是 AGENT:$R13_CID" || fail "响应里的 target 不对"
    [ "$(jq_ "d['joinUrl']")" = "/join/$R13_TOKEN" ] && [ -n "$R13_TOKEN" ] \
      && ok "铸造响应里有明文票与分享链接 (只此一次)" || fail "joinUrl 与 token 对不上: $(jq_ "d['joinUrl']")"

    # 20b: 与 R10 同一条不变量 —— 明文只在响应里出现一次, 库里只有哈希。
    R13_LEN=$(sql "select char_length(token_hash) from session_invitation where id='$R13_INVITATION'")
    [ "$R13_LEN" = "64" ] && ok "库里存的是 64 位哈希" || fail "token_hash 长度 = '$R13_LEN'"
    R13_LEAK=$(sql "select count(*) from session_invitation where token_hash='$R13_TOKEN'")
    [ "$R13_LEAK" = "0" ] && ok "明文票没有进库" || fail "明文票出现在 token_hash 列里"

    # 20c: 信送到了没有 —— 数字人那条链跑在邮箱线程上, 给它最多 15 秒。
    R13_MODE=""
    R13_LINE=""
    for _ in $(seq 1 30); do
      NEW=$(tail -n +"$((LINES_BEFORE + 1))" "$LAP_LOG")
      R13_LINE=$(echo "$NEW" | grep -F "[AgentInvitation]" | grep -F "$R13_CID" | head -1 || true)
      if [ -n "$R13_LINE" ]; then
        case "$R13_LINE" in
          *"不作处置"*)      R13_MODE="ignored" ;;
          *"接受了邀请"*)    R13_MODE="accepted" ;;
          *"从开着的门"*)    R13_MODE="accepted" ;;
          *"谢绝了邀请"*)    R13_MODE="declined" ;;
          *)                 R13_MODE="other" ;;
        esac
        break
      fi
      sleep 0.5
    done

    case "$R13_MODE" in
      ignored)
        ok "信走完了整条链 (应用平台 → 单向门 → 邮箱 → 邀请处理器); 而 LLM 不可用 ⇒ 它没有做决定" ;;
      accepted)
        ok "信走完了整条链, 数字人接受邀请进了场: $R13_LINE" ;;
      declined)
        ok "信走完了整条链, 数字人想过之后谢绝了: $R13_LINE" ;;
      other)
        ok "信走完了整条链, 处置结果是: $R13_LINE" ;;
      *)
        fail "15 秒内没有看到 AgentApplicationInvitationHandler 处置这封邀请 —— 平台事件通道断了?
        日志: $LAP_LOG (收件人 $R13_CID)" ;;
    esac

    if [ -n "${LAP_EXPECT_AGENT_JOIN:-}" ] && [ "$R13_MODE" != "accepted" ]; then
      fail "LAP_EXPECT_AGENT_JOIN=1 但数字人没有进场 —— 服务跑的多半还是 mock LLM"
    fi

    # 20d: 决定了什么, 就得在数据上兑现什么。三种结果各自要成立的那一条:
    R13_SEATED=$(sql "select count(*) from application_session_participant
                       where session_id='$R13_SESSION' and principal_type='AGENT'
                         and principal_id='$R13_CID'")
    R13_LEDGER=$(sql "select count(*) from timeline_event
                       where person_id='$R13_CID'
                         and event_type in ('APPLICATION_INVITATION_ACCEPTED','APPLICATION_INVITATION_DECLINED')")
    if [ "$R13_MODE" = "accepted" ]; then
      [ "${R13_SEATED:-0}" -ge 1 ] && ok "它真的坐在这一场里了 (参与者行已落库)" \
        || fail "日志说进场了, 参与者表里却没有它"
      R13_ACC=$(sql "select count(*) from timeline_event where person_id='$R13_CID'
                       and event_type='APPLICATION_INVITATION_ACCEPTED'")
      [ "${R13_ACC:-0}" -ge 1 ] && ok "账本里留下了 APPLICATION_INVITATION_ACCEPTED" \
        || fail "进了场却没有记一笔 —— 事后查不出它是怎么进来的"
    else
      [ "${R13_SEATED:-0}" = "0" ] && ok "它没有进场 (参与者 0 行)" \
        || fail "数字人没答应, 场上却多了一行参与者"
      # 未兑的票不该被烧掉 —— used_count 是"这张票被用掉几次", 而一次没决定的邀请没有用掉它。
      R13_USED=$(sql "select used_count from session_invitation where id='$R13_INVITATION'")
      [ "${R13_USED:-0}" = "0" ] && ok "票还是未使用的 (used_count=0)" \
        || fail "票被用掉了 $R13_USED 次, 但没人进场 —— 有人在没决定的时候动了票"
    fi

    # 20e: **这一条是 R13 的核心**: 「没决定」不等于「谢绝」。
    if [ "$R13_MODE" = "ignored" ]; then
      [ "${R13_LEDGER:-0}" = "0" ] \
        && ok "账本里一条都没有 —— 「没做决定」没有被写成「谢绝」" \
        || fail "LLM 不可用却往账本里写了 $R13_LEDGER 条邀请经历: 一次服务抖动被记成了它的态度"
    elif [ "$R13_MODE" = "declined" ]; then
      [ "$(sql "select count(*) from timeline_event where person_id='$R13_CID'
                  and event_type='APPLICATION_INVITATION_DECLINED'")" = "1" ] \
        && ok "想过之后说不, 也真的记了一笔 (谢绝是它的决定, 值得记)" \
        || fail "谢绝了却没有记账"
    fi

    # 清理: 会话那一半不挂在伴侣下面(§2: Application 与 Digital Human 互不相识), 只能显式删;
    # 伴侣那一半走 REST(它自己有一整套级联)。
    curl -s -X DELETE "$BASE/api/companions/$R13_CID" -H "Authorization: Bearer $AUTH" \
      -o /dev/null 2>/dev/null || true
    exec_sql "delete from session_permission where participant_id in
                (select id from application_session_participant where session_id='$R13_SESSION')" \
      >/dev/null 2>&1 || true
    exec_sql "delete from application_session_participant where session_id='$R13_SESSION'" \
      >/dev/null 2>&1 || true
    exec_sql "delete from session_invitation where session_id='$R13_SESSION'" >/dev/null 2>&1 || true
    exec_sql "delete from application_session where id='$R13_SESSION'" >/dev/null 2>&1 || true
    exec_sql "delete from messages where conversation_id in
                (select id from conversations where companion_id='$R13_CID')" >/dev/null 2>&1 || true
    exec_sql "delete from conversations where companion_id='$R13_CID'" >/dev/null 2>&1 || true
    exec_sql "delete from companions where id='$R13_CID'" >/dev/null 2>&1 || true
  fi
fi

# ── 断言 15: 共享世界 —— 一行 resource, 两个 principal ──
# 这是整个 LAP 最想证明的一句话: 真人和数字人不是各玩各的, 他们操作的是同一个东西。
# 判据不是"两边都返回 200", 而是数据行本身: 一行 resource、一条会话、两个不同的 principal
# 各自在这条会话上留下过 action_invocation。
# 数字人那一半需要真实 LLM(数字人拒绝在 mock 下动手 —— 见断言 11), 所以这一段在 mock 环境下
# 跳过; 真人 + MCP Agent 的那一半由断言 14 在同一个 URI 上证明, 两条合起来覆盖完整的"共享世界"。
note "断言 15: 共享世界 —— 一行 resource, 两个 principal"
if [ "$DH_MODE" = "moved" ] && [ -n "$DH_URI" ]; then
  ROWS=$(sql "select count(*) from resource where uri='$DH_URI'")
  [ "$ROWS" = "1" ] && ok "resource 表里这一局只有一行" || fail "resource 有 $ROWS 行 ($DH_URI)"

  SID=$(sql "select session_id from resource where uri='$DH_URI'")
  ACTORS=$(sql "select count(distinct principal_id) from action_invocation where session_id='$SID'")
  [ "${ACTORS:-0}" -ge 2 ] && ok "同一条会话上有 $ACTORS 个不同的 principal 动过手" \
    || fail "同一条会话上只有 ${ACTORS:-0} 个 principal —— 数字人那一手不在这一局里"
else
  skip "共享世界: 数字人的应手落在同一行 resource — 需要真实 LLM (当前 $DH_MODE)"
fi

echo ""
if [ "$FAIL" = "0" ]; then
  echo "✅ 验收通过 ($SKIPPED 项未到轮次, 已跳过)"
else
  echo "❌ 验收未通过"
  exit 1
fi
