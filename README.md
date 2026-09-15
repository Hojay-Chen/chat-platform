# chat-platform — 聊天平台 + 应用平台（仓 1）

> **2026-09-14 G2 起本仓只承载聊天平台 + 应用平台**：digital-human-platform
> 已整体迁往独立仓库 [simulation-agent-platform](../simulation-agent-platform)
> （仓 2：数字人认知链 + 两个服务 —— 功能服务 8091 / 对外 OpenAPI 8092）。
> 本仓经 `contract` 项目的 SPI 端口与仓 2 相见（`gradle :contract:publishToMavenLocal`
> 发布 `com.luxera:contract:1.0.0` 供其引入）。下文历史章节中的 `digital-human-platform`
> 相关描述均为 V10/LAP 时代的记录，以本注记为准。
>
> **两个域名，各管各的（G8 起）**：`companion.luxera.top` → 本仓（前端静态 + `/api/**`
> → 8081），`agent.luxera.top` → 仓 2（控制台静态 + `/api/v1/openapi/` 与 `/api/health`
> → 8092 + 其余 `/api/` → 8091）。**浏览器只跟聊天平台的后端说话** —— 聊天平台调仿真
> Agent 平台是**后端调后端**：伴侣域请求由 8081 在服务端转给 8091，前端不做任何分流。
> 详见 §G8。
>
> **不是 Chatbot**：拥有稳定人格、连续人生、持续记忆，随时间与用户建立关系，并在合适的时候主动找你。
>
> 设计依据：《Persistent AI Companion 产品与技术设计方案》（107 节）。当前为 **Digital Person 版**：
> Chat Platform 是软件，Agent 是独立存在的人 —— 消息永不丢、关系是真实状态、世界持续运行、行为由中央行为选择器决定。

---

## LAP 应用运行时 · 数字人操作应用生态的安全底座（2026-09，第一轮）

> **本轮目标**：把「真人与 Agent 共玩应用」从**硬编码的 Game POC**升级为**可扩展的应用生态底座**。
> 核心原则：Application ≠ Digital Human —— Agent 认知链不直接依赖任何具体应用类，
> 而是通过 `ActionRuntime` 表达意图，由权限引擎决定能否执行，审计日志全程可追溯。
>
> **后续**：本节描述的 `application/runtime`、`application/permission`、`application/builtin/*`
> 已在 LAP v1 的 R3 轮**整体搬进第 6 个 Maven 模块 `application-platform`**
> （包名 `com.luxera.companion.application.*` 不变），数字人侧改为只依赖 `contracts` 的 SPI 端口。
> 现状与后续路线见下方 **「LAP v1 · 应用平台」**。

### 核心成果（本轮结束时全量 294 测试全绿）

1. **ActionRuntime 统一动作执行入口**（`application/runtime`）：
   `ActionRuntime`（接口）+ `DefaultActionsRuntime`（实现 + actionId→Handler 注册表）。
   Agent 只说"我要执行 `game.make_move`"（actionId + 参数 + 幂等键），不 import 具体游戏类。
   **加第二个应用（如象棋）只需写一个 Adapter，AgentRuntime 零改动**。

2. **TicTacToeApplicationAdapter**（`application/builtin/tictactoe`）：
   把现有 `TicTacToeGameService` 包装成 4 个 LAP 动作，`@PostConstruct` 注册：
   - `game.create`（开局，WRITE/LOW）
   - `game.state`（读局面，READ/NONE）
   - `game.make_move`（落子，WRITE/LOW）
   - `game.surrender`（认输，WRITE/LOW）

3. **PermissionEngine 权限引擎**（`application/permission`）：
   **LLM 永远没有权限决定权** —— 认知链只提"行动意图"，能否执行由策略决定。
   风险五级 → 决策映射：`NONE/LOW`→放行、`MEDIUM`→需确认、`HIGH/CRITICAL`→拒绝。
   `RiskLevel`（NONE/LOW/MEDIUM/HIGH/CRITICAL）× `PermissionLevel`（READ/WRITE/EXECUTE）。

4. **Action Log 审计**（`application/audit`）：`dh_application_action_log` 表记录每次动作——
   谁（companion）/ 对什么应用 / 结果 / 权限决策 / 幂等键 / 因果链。
   可追溯「为什么 Agent 给我下单了」这类问题。

5. **ActionDescriptor 声明式描述**（`application/domain`）：
   `actionId/appCode/description/permission/risk/attentionPolicy` 六元组。
   LLM 读描述符而非读 Java Class；`AttentionPolicy` 复用四级感知（NONE/SUBCONSCIOUS/AWARE/FOCUSED），
   防止高频噪音事件（如播放进度）淹没 Agent 认知链。

6. **AgentRuntime 解耦**：`onApplicationEvent` 改走 `ActionRuntime.execute("game.state")` 读局面、
   `ActionRuntime.execute("game.make_move")` 落子，删除对 `TicTacToeGameService` 的直接依赖。

### 应用操作链路

```
用户落子 → GameSession → APPLICATION_EVENT
    → EventProcessingChain → EventRouter
    → AgentRuntime.onApplicationEvent
        → ActionRuntime.execute("game.state")      ← 读局面（经权限+审计）
        → LLM 局面评估（结构化输出 position + reason）
        → ActionRuntime.execute("game.make_move")  ← 落子（经权限+审计）
        → RealityLedger（APPLICATION_ACTION_EXECUTED）
```

### LAP 命名约定

| 概念 | 名称 | 实现 |
|------|------|------|
| 应用运行 | Application Runtime | `ActionRuntime` / `DefaultActionsRuntime` |
| 应用动作 | Action | `ActionDescriptor`（含 JSON 约束的 Java 常量描述） |
| 应用权限 | Permission | `PermissionLevel`（READ/WRITE/EXECUTE）+ `RiskLevel` |
| 应用审计 | Action Log | `dh_application_action_log` |
| 第三方接入 | Application Adapter | `TicTacToeApplicationAdapter`（首批） |

> **诚实说明（V10 当时的边界，已于 LAP v1 落地）**：完整声明式 `lap-manifest.json` 解析、MCP Adapter、多语言 SDK、App Store 属"有真实第三方接入需求后再做"的部分，本轮未做。当前 `ActionDescriptor` 用 Java 常量描述，`describe()/listActions()` 已返回描述符，届时只换数据来源、不改调用方。
>
> **后续**：`lap-manifest.json` 已于 **LAP v1 R4** 落地（`ApplicationManifest` + `ManifestParser` /
> `ManifestValidator` / `ManifestRegistrar`），MCP Adapter 已于 **R6** 落地（`POST /mcp`，
> 见「LAP v1 · 应用平台」一节）。`ActionDescriptor` 已被 `contracts.application.ActionSpec` 取代，
> 本次拆分后**不再存在两份**。

---

## V10 完整重构 · 三系统物理解耦 + 拟人化表达层（2026-09）

> **本轮依据**：《Companion Agent V10 完全解耦实施方案》9 轮重构 —— 把"V10 词汇贴在 V9 骨头上"的
> 现状升级为真正的三系统解耦：Chat Platform ↔ Simulator（DHCP v1 WebSocket 协议）↔ Digital Human，
> 并落地完整拟人化表达层，让用户聊天时无法分辨对方是真人还是数字人。

### 物理结构 —— 六个 Maven 模块，边界由 classpath 强制

```
backend/
├── pom.xml                   父 POM（packaging=pom，spring-boot-starter-parent 2.7.18）
├── contracts/                纯协议模块：DTO / enum / SPI 端口，无 Spring、无 JPA
├── platform-kernel/          共享内核：auth(JwtUtil) / config / common(转换器) / outbox 实体
├── chat-platform/            聊天平台：conversation / event / simulator(WS 服务端)
├── digital-human-platform/   数字人平台：32 个包（agent/life/memory/emotion/... ）+ WS 客户端
├── application-platform/     应用平台：manifest / 能力与动作发现 / Resource / Action 网关 / 权限 / 内置参考应用
└── bootstrap-app/            瘦启动器：唯一同时看得见三方的模块，repackage 出可执行 jar
```

> **仍然是同一仓库**（单仓多模块），但**已经是各自独立的工程**：任何人删掉 `chat-platform/` 目录，
> `digital-human-platform/` 依然能 `mvn test` 独立跑（测试期用 `DigitalHumanTestApplication` +
> `InMemoryChatWorld` 顶替聊天平台）。反过来也成立，应用平台同理 —— 这不再是"一个模块里两个包"
> 式的假解耦。

### 解耦到底解在哪：六个 SPI 端口（Ports & Adapters）

跨平台调用**不再有任何 Java 直接依赖**，只剩 `contracts.spi` 里六个接口；两侧各写各的适配器：

| 端口（`contracts.spi`） | 方向 | 谁实现 | 用途 |
|---|---|---|---|
| `ChatWorldPort` | DH → Chat | chat 的 `ChatWorldAdapter` | 数字人读会话/写消息（她的"外部世界"） |
| `CompanionDirectoryPort` | Chat → DH | DH 的 `CompanionDirectoryAdapter` | 聊天平台查"这个 companion 是谁" |
| `SimulatorAccessPort` | DH → Chat | chat 的 `SimulatorAccessAdapter` | 数字人换设备 token |
| `ApplicationRuntimePort` | DH → Application | 应用平台的 `ActionGateway`（没有 `*Adapter` 类） | 读 Resource / 问能做什么 / 执行 action |
| `ApplicationEventSink` | Application → DH | DH 的 `DhApplicationEventSink` | 应用通知数字人"有事发生"（**单向门**） |
| `ApplicationCatalogPort` | Chat → Application | 应用平台的 `ApplicationCatalogAdapter` | 聊天里看/开/分享应用 —— 让 chat 在不认识应用平台的前提下做到 §63 |

> **真人与 Agent 走同一条路**：没有"Agent 专用 API"。数字人操作应用时和真人一样经过
> `ApplicationRuntimePort` → 同一个 Action 网关 → 同一个 Resource。数字人**不解析任何应用状态、
> 不判断任何业务规则、不认识任何一个具体应用** —— 棋盘长什么样、轮到谁，都由应用回答。

数据面则经 **DHCP v1 WebSocket 协议**（`contracts.dhcp`）流动，聊天平台只看见一台"机器用户设备"，
完全不知道 Companion 的存在。

### 重构成果（9 轮，当时全量 294 测试全绿；应用平台拆出后为 329，LAP v1 全部落地后为 646 —— 见下一节）

**架构解耦（R1-R4）**：
1. **Maven 多模块拆分**：见上（V10 落地时为五模块，LAP v1 拆出 `application-platform` 后为六个）。
   `platform-core` 过渡单体已彻底删除（679 个文件），
   三个平台之间**唯一的编译期联系是 `contracts`**。
   边界守卫三重：`scripts/check-v10.sh`（包归属互斥 + 源码引用 + pom 依赖图）、
   `contracts` 的 `ArchitectureTest`（自足性白名单）、`bootstrap-app` 的
   `ModuleBoundaryArchitectureTest`（ArchUnit 对字节码断言三方互不依赖）。
2. **DHCP v1 协议（contracts.dhcp）**：`DhcpFrame` + 11 种帧类型（CONNECT/AUTH/SUBSCRIBE/EVENT/
   EVENT_ACK/COMMAND/COMMAND_RESULT/PING/PONG/ERROR/DISCONNECT）+ 配对/令牌/命令 DTO。
3. **Simulator WebSocket 服务端（R2）**：`/ws/simulator` JSR-356 端点 ——
   `SimulatorPairingService`（6 位配对码 + 一次性 secret + bcrypt + tokenVersion 吊销机制）、
   `SimulatorTokenService`（短期 JWT，独立密钥）、`SimulatorCommandDispatcher`
   （4 种命令 scope 校验 + 幂等）、`SpringConfigurator`（端点 Spring 管理）。
   `WebSocketConfig` 用 `WebServerInitializedEvent` 延迟导出（解决 WsSci 时序问题，兼容 MOCK 测试）。
4. **ChatSimulatorConnector（R3，DH 侧 WS 客户端）**：每 companion 一设备连接（CONNECT→AUTH→SUBSCRIBE
   →心跳→命令收发）；`ChatSimulatorClient` 门面（sendMessage/readMessages/updateDeliveryStatus/
   listConversations 全走 WS 命令，idempotencyKey 幂等）。`MessageCoreService.afterCommit` 推送
   `chat.message.delivered` WS 事件给在线 simulator —— **数据面彻底经 WS，共享 JPA 依赖断开**。

**Strangler 热路径切换（R4/R6）**：
5. **V10HotpathGateway 接入 AgentRuntime.process**：三模式（纯 V9 / shadow 影子对比 / enabled 切流）。
   V10 感知决策编排器（PerceptionDecisionOrchestrator）在 V9 pipeline 之前评估：
   shadow 模式只记录 V10 vs V9 决策 diff（`ShadowDecisionRecorder`）；enabled 模式下
   `NOT_PERCEIVED` → 短路不处理、`DELAY` → 短路延迟回复。**防御式设计：任何 V10 异常不阻断 V9 主链路，
   绝不污染主事务**。配置开关 `app.v10.hotpath.{enabled,shadow}`。

**拟人化表达层（R5，8 引擎全套）**：
6. **`digitalhuman.expression` 包**（每项独立开关 `app.v10.human-likeness.*`）：
   - `TypingRhythmEngine`：思考间隔 200-1500ms、中文 25-40 字/分钟、段间隔 ×(1+stress×0.5−intimacy×0.2)
   - `TypoEngine`：自然错字（拼音近音 的/得/地 + 形近字 已/己 + 20% 漏字），非中文不碰，
     受 精力↓/压力↑/困倦↑ 加成（各 +0.015）
   - `HesitationEngine`：低注意/低信心时句尾 `...`/`嗯...`/句首 `那个.../其实...`/弱化词
   - `EmotionContinuityFilter`：近 5 分钟情绪基调连贯，强度差 >0.4 提示微调（不突变）
   - `PhysioExpressionFilter`：maxChars = 200×(energy×0.4+(1−sleep)×0.3+(1−illness)×0.3)，
     周末 ×1.15 松散；疲惫时错字率自动升高
   - `PersonaVoiceCompiler`+`PersonaVoiceProfile`：口音/口头禅/微习惯/emoji 率/句尾风格 注入 stablePrefix
   - `PersonaFingerprint`：8 字节 SHA-256 指纹注入 userPrompt 首行（隐式区分，不同 companion 不趋同）
   - `MemoryDriftPolicy`：亲密度 <0.7 禁主动引用记忆、同一记忆 24h 不重复引用、引用 ≤30 字摘要
7. **ConversationRuntime 集成**：稳定层追加（生理预算/情绪连贯/人设口音）→ prompt 缓存 hash 参与计算；
   LLM 输出后处理（错字/犹豫只改最终文本，不进 prompt 避免污染缓存）→ 输出闸门不破。
   `ConversationRequest` 增加拟人化输入（personaLanguage/emotion/physio/relationship，安全默认值向后兼容）。

**生产修复 + 应用平台（R9）**：
8. **事务 rollback 毒化修复**：`PendingMessageReevaluationJob.run()` 移除批量 `@Transactional`
   —— 原先单条 reevaluate 异常被 catch 吞掉但事务已标记 rollback-only，提交时抛
   `UnexpectedRollbackException`，**一条坏消息会堵塞整批复查**（生产隐患，非测试问题）。
9. **Application Platform 骨架 + Game POC（V10 §32-§36）**：`dh_application` 表 + `ApplicationRegistry`
   （种子 hello-world/tictactoe）+ 井字棋 `GameSession`（局面 JSON、胜负/平局判定、GAME_EVENT 事件）+
   `/api/v10/applications` + `/api/v10/games/tictactoe/*` REST 端点。真人可与 Agent 对弈。
10. **部署脚本修正**：`scripts/deploy.sh` / `backend/run.sh` 适配多模块
    （可执行 jar 由 `bootstrap-app` 组装：`bootstrap-app/target/companion-platform-bootstrap-1.0.0.jar`，
    单进程部署；两平台分进程拓扑见 `docs/ARCHITECTURE.md`）。

### 新增数据表

| 表 | 用途 |
|----|------|
| `simulator_devices` | Simulator 设备（配对码/secretHash/tokenVersion/状态机 PAIRING→ACTIVE→REVOKED） |
| ~~`dh_application`~~ | ~~Application Platform 应用注册（code/manifest/权限）~~ —— **R8 已 DROP**（`scripts/lap-drop-legacy.sh`） |
| ~~`dh_game_session`~~ | ~~井字棋对局（roomId/局面 JSON/胜负状态）~~ —— **R8 已 DROP** |
| ~~`dh_application_action_log`~~ | ~~LAP 动作审计~~ —— **R8 已 DROP**（旧表的 `permission_decision` 是废字段，新表把权限判定与执行结果分开记） |

### 重构验收

- `mvn clean test`：**294 测试全绿（0 失败 0 错误）** —— contracts 10 / platform-kernel 0 /
  chat-platform 24 / digital-human-platform 228 / bootstrap-app 32。
  含 16 个 Simulator WS 测试（真实 WebSocket 握手 + 4 种命令 + 落库校验）、8 个拟人化引擎单测、
  4 个井字棋判定测试、5 个权限引擎单测、4 个动作描述符单测、4 个 ActionRuntime 集成测试
- **打包产物冒烟**：`java -jar bootstrap-app/target/companion-platform-bootstrap-1.0.0.jar` 起服后
  `BASE=http://127.0.0.1:8081 bash scripts/check.sh` → **✅ 全量验收全部通过**（11 组：
  schema / 创建伴侣 E2E / persons 关系 / 消息同步落库 / clientMessageId 幂等 /
  event_log + SSE Last-Event-ID 回放 / BehaviorEngine / 会话线程 / Anti-AI 真人感 100% /
  认知会话与计划与 LLM 可观测 / prefix cache）
- `scripts/check-v10.sh`：边界守卫（包归属互斥 / chat 禁引 digitalhuman / DH 禁引 chatplatform /
  contracts 谁都不引 / pom 依赖图）—— 已注入违规探针验证其**非空转**（探针触发时 EXIT=1）
- 全部轮次独立提交推送，每轮全量测试绿

### 已知的历史遗留（诚实说明）

- `chore: .gitignore` 只盖住了单体时代的 `backend/target/`，多模块后各子模块 `target/` 曾被误提交
  151 个文件，已 `git rm --cached` 并补 `backend/**/target/` 规则。
- 顶层包之间存在少量既成循环依赖（拆分前就有，与本次拆分无关），因此
  `ModuleBoundaryArchitectureTest` **不做**顶层包循环断言 —— 与其写一条注定被放宽的规则，不如不写。
  模块层面的依赖方向由 classpath + pom 检查 + ArchUnit 规则保证（V10 时三条，LAP v1 扩到六条）。

---

## G1-G2 物理拆分（2026-09，G1/G2 已完成）

> **目标**：聊天平台 + 应用平台一个仓库一个服务（由聊天平台启动类拉起），
> 仿真 Agent 平台独立仓库独立进程；两套全新前端；仿真 Agent 的 OpenAPI 服务与聊天平台内的对接功能。
> 分轮：**G1 仓 1 Gradle 化 ✅ → G2 仓 2 骨架+DH 迁移 ✅ → G3 跨服务 HTTP 化 ✅ → G4 OpenAPI 服务 ✅ → G5 聊天前端 ⚠️ 方向被推翻 → G6 Agent 管理前端 ✅ → G7 联调部署 ✅ → G8 前端单入口（后端转伴侣域）✅**。
>
> **G5 被推翻的地方**：它让**前端**按路径段分流、加 `/agent` 前缀直连 8091。这是错的 ——
> 聊天平台的前端只能调聊天平台的后端，聊天平台调仿真 Agent 平台指的是**后端调后端**。
> G8 已改为浏览器单入口、伴侣域由 8081 在服务端转发。详见 §G8。

### G3 已完成（2026-09-15）—— 跨服务 HTTP 化

四个跨服务 SPI 端口全部落 HTTP，两服务从此真正独立部署（chat:8081 ↔ agent-server:8091）：

- **chat 侧**（本仓）：`AgentPlatformIntegration` 占位删除，`HttpCompanionDirectoryAdapter`
  取代（requireOwned 404/不可达 → 诚实拒绝；onUserMessage → fire-and-forget 吞一切失败）；
  新增 `/internal/**` 面：`InternalChatWorldController`（ChatWorldPort 19 方法）、
  `InternalApplicationRuntimeController`（ActionGateway 全入口）、`InternalSimulatorAccessController`。
- **鉴权**：服务间 HMAC-SHA256 签名（`X-Lap-Timestamp`/`X-Lap-Signature`，密钥
  `AGENT_PLATFORM_INTERNAL_KEY` 两仓同值；未配 → /internal 503 死端点）。`InternalAuthFilter`
  先验签再放行；Spring Security 的 `anyRequest().authenticated()` 放行 `/internal/**`
  —— **这是 MCP/Developer API 那个坑的第三次踩点，`InternalEndpointSecurityTest` 钉死它**。
- **契约修复**：`MessageView`/`ConversationView` 加 `@Jacksonized` —— 单进程时代这两个
  Lombok 类型只序列化，G3 HTTP 面 first-time 反序列化时无 creator 直接炸。contract
  artifact 重新 publishToMavenLocal。
- **验收**：`scripts/check-split.sh` S1-S8 全绿 —— 双进程同起、/internal 面鉴权（无签名 401/
  对签名 200）、同一 JWT 两服务认（**G2 只验过 401，JWT 认识链是 check-split 抓出的缺口，
  JwtUtil/JwtAuthenticationFilter 补进仓 2 common**）、chat 发消息经 requireOwned 跨 HTTP、
  fire-and-forget 认知通知真到对岸（processed_event 收据）、8091 缺席时 chat 照常（韧性）、
  重起恢复。另抓出并修复：`processed_event.event_id` varchar(96) → 255（单进程时代
  认知事件不落这张表，G3 拆分后 first-time 落库撞上确定性 eventId 143 字符）。



`backend/digital-human-platform`（443 文件）整体迁往仓 2 `simulation-agent-platform`
（含 Maven 遗物 `backend/pom.xml`、`backend/run.sh` 一并清除，本仓 backend/ 目录不复存在）：

- 仓 2 Gradle 多项目：`common`（kernel 类随迁，拷贝不剪切，两仓各自演化）/
  `backend/digital-human-platform`（原路径名不变 —— git follow 穿历史的前提）/
  `server`（功能服务 8091）/ `openapi`（对外 API 服务 8092，骨架）；
- 仓 2 测试 **309 全绿**（G1 后这批测试从未在 Gradle 环境跑过，本次首次全绿）；
  双 jar 起进程冒烟通过（8091 认知链定时任务运转 / 8092 health UP）；
- 边界守卫 `scripts/check-agent.sh`（包归属互斥 / DH 白名单 import / common 不识 DH / 依赖图）；
- git 历史经 ours-merge 嫁接保留全链（companion-agent 单体时代 → 现在），
  本仓继续作为历史主链存在。

### G1 已完成（仓 1 Gradle 化）

**结构**（用户拍板的组织方式）：一个根 `build.gradle` 多项目；`contract`（对外契约，
**零仓内依赖**，`publishToMavenLocal` 成 `com.luxera:contract:1.0.0` 供 Agent 仓库引入）、
`common`（kernel 演化：auth/JWT/outbox/异常）、`application`（LAP）、`chat`（主体，
**启动类 `ChatPlatformApplication` 在这里**，端口 8081 不变）。Java 包名刻意不动
（`com.luxera.companion.*`）—— 改包名是零收益纯风险，目录分业务在 Gradle 项目边界上表达。
Gradle 8.14.3（腾讯镜像直连可下），Spring Boot 2.7.18/JDK 17 依赖全部走 `mavenLocal()`
+ 阿里云镜像复用，无需重新下载。

**验收全链绿**：`check-v10.sh`（改为守 Gradle 四项目：包归属互斥 10 包 / 源码引用 /
build.gradle 依赖图 / contract 零仓内依赖）→ `gradle test` **466 全绿**
（contract 23 / application 399 / chat 44）→ `:chat:bootJar`（43MB fat jar）→
真进程冒烟（8081 起、登录、LAP、MCP）→ `check.sh`（`CHECK_MODE=split`：表结构+登录，
伴侣端到端段诚实跳过——它要验的链路从"一个进程内"变成"两服务之间"，G3 后由
`check-split.sh` 承担）→ `check-lap.sh`（`LAP_AGENT_MODE=split`：断言 11/17/19/20 涉及
数字人侧的诚实跳过，平台侧语义全部照验）→ `check-remote-app.sh` 全绿 →
`check-ecosystem.sh` **E1–E7 全绿** → 前端 **60 测试** + build 绿（G5 后含 `client.route.test.ts` 36 断言）。

**拆分中暴露并修掉的三个结构事实**（都是"单进程时代隐形"的）：
1. **测试桩与真实现的 Bean 冲突**：conversation 组测试的假应用目录（纸飞机 stub，
   "chat 不认识任何具体应用"的判据）与真 LAP 适配器在同一个 classpath 上撞了 ——
   测试启动类移出扫描前缀（`com.luxera.chatframework`）+ 显式 `scanBasePackages`/`@EnableJpaRepositories`。
2. **架构守卫按新 classpath 改写**：`ModuleBoundaryArchitectureTest` 从"三模块互斥"
   改为守"chat↔application 经 contract 相见 + common 不认识两平台"；§118（AgentRuntime）
   随 DH 迁仓 2；`BeanNameCollision` 的覆盖面下限从 200 校准到拆分后的 114+。
3. **`CompanionDirectoryPort` 的启动缺口**：DH 搬走后 chat 主进程没有这个端口的实现
   —— 用 `@ConditionalOnMissingBean` 占位（`requireOwned` 诚实拒绝、`onUserMessage` 静默，
   与"进程外 DH 不在场"等价），G3 落 HTTP 适配器时自动退位。这正是 fire-and-forget
   语义（V10 §2.1）的红利：Agent 平台缺席时聊天平台不残废。**（G3 已兑现：占位删除，
   `HttpCompanionDirectoryAdapter` 常驻；见 G3 段。）**

### G5 已完成但**方向已被 G8 推翻**（2026-09-15）—— 聊天前端双服务适配

> ⚠️ **本节描述的 `/agent` 前缀分流方案已经不存在了。** 保留原文只为记录当时的
> 判断与它为什么错 —— 判据（按"路径段"猜端点归属）本身是错的，见 §G8。现在前端
> 零分流、`route()` 已删除、`/agent/api` 通道已从 vite 与 nginx 两侧移除。

前端（`frontend/`）在 G1 前就已是成熟的暖棕 IM 风（侧栏会话、气泡、已读回执、
打字指示器、连发聚合、SSE 持久事件流、应用卡片、六个抽屉面板），**G5 的硬伤不是
"从零建前端"，而是拆分后前端从没适配双服务**——G1 把伴侣域（companions CRUD /
memories / relationship / life / self / reminders / notifications / user-model /
state / reflections）迁去 8091，而会话/消息/事件流（conversations / messages /
events / threads / applications）与 auth、应用平台（/api/v1）仍留在 8081。前端
`client.ts` / vite 代理还单目标打 8081，记忆与关系的请求全部断在 404。

**分流策略**——前端 URL 不变，`client.ts` 的 `route(url)` 在一处集中按"路径段"
判定加 `/agent` 前缀（G7 生产 nginx 同一套规则）：
- **8091（`/agent/api`）**：`/api/companions` 本身（列表/创建/compile/preview）、
  `/{id}`（详情/删除）、`/{id}/{memories|relationship|life|self|reminders|
  notifications|user-model|state|reflections|persona|life-events}`
- **8081（`/api`，不加前缀）**：`/api/auth`、`/api/v1/**`、
  `/{id}/{conversations|messages|events|threads|applications}` 及其子路径
- `vite.config.ts` 双目标代理（顺序敏感：`/agent/api` 在 `/api` 前，`rewrite`
  去 `/agent` 前缀转发 8091）
- 死代码清理：`streamPost`（全库无引用）与 `emitBlock` 一并删除
- `openEventStream` 的 fetch 也过 `route()`（`/events` 经判定归 8081，行为不变）

**验收**：`client.route.test.ts` **36 断言**钉死归属判定（含 query/尾斜杠/段名
冲突的边界用例）；`scripts/check-frontend.sh` F1–F5 端到端验分流（两服务同起 →
登录 → /api/auth/me 经 vite 走 8081 → /agent/api/companions 走 8091 →
反向验证 /api/companions 不加前缀在 8081 回 404）。前端 60 测试 + build 绿。

### G6 / G7 已完成（2026-09-16）—— 仓 2 控制台与其部署（本仓不动）

G6/G7 全部落在**仓 2**（[simulation-agent-platform](../simulation-agent-platform)），
本仓源码与前端均未改动 —— 两个前端是两套独立产物（用户拍板），靠 nginx 域名分流
共存，而不是塞进同一个 SPA。

- **G6** 仓 2 新增 `frontend/` 管理控制台（agent 列表/详情/创建、API 客户端发放与
  吊销、agent 实时状态）。与 G5 那条 `route()` 的**关键差别**：控制台只打**一个**
  后端 8092，分流不在"主机"而在**"面"**（管理钥 `X-Admin-Key` vs 客户端钥
  `Bearer sap_...`）—— `faceOf(url)` 判定该带哪把钥匙。计划里"状态走 8091"的
  那一项落地时被否掉了：8091 的 `StateController` 要用户 JWT，控制台没有。
- **G7** nginx 新增 `agent.luxera.top`（`deploy/nginx/agent.luxera.top.conf` 在仓 2
  版本库，`scripts/deploy.sh` 安装），与 `companion.conf` **并存互不干扰**：
  - `agent.luxera.top` → 仓 2 控制台 `/var/www/agent` + `/api/` → 8092（**单上游**。
    **G8 已改为按前缀分两个上游**：`/api/v1/openapi/` 与 `/api/health` 仍归 8092，
    其余 `/api/` 归 8091 —— 平台自身功能面得在自己域名下可达）
  - `companion.luxera.top` → 本仓前端 `/var/www/companion` + **双上游**：
    `/api/` → 8081，`/agent/api/` → 8091（去 `/agent` 前缀，与 G5 vite 的
    `path.replace(/^\/agent/, '')` 一一对应）。**这一条是 G7 补上的 G5 欠账** ——
    G5 只落了 dev 侧（vite rewrite），README 里那句"G7 生产 nginx 同一套前缀规则"
    在本轮之前没有落点：线上伴侣域请求会全部落到 `location /` 的 SPA 回退上，
    dev 一切正常而线上静默 404。配置源头是本仓 `deploy/nginx/companion.conf`。
    **G8 已把这条第二上游删除** —— 现在只有 `/api/` → 8081 一个上游，
    `/agent/api/` 不再存在。
  - 鉴权分层：控制台静态页套 Authelia 前门，`/api/**` **不套**（三方机器客户端
    拿 401 而不是 302 登录跳转）。这条差异是本轮部署最要紧的一处，详见仓 2 README §7 G7。
    `companion.luxera.top` **未套** Authelia（现状保持，是否加套由用户定）。
- **G7 首次真机部署在本仓抓出四个缺陷**（都是"单看一个仓完全看不出来"的那类）：
  1. `scripts/deploy.sh` 的 `NGINX_SRC` 指向 `infrastructure/nginx/sites/` —— 那棵树
     停在 8 月（没有 `/agent/api` 落点），**每次跑 deploy.sh 都会把线上配置悄悄倒退回
     拆分前**。已改为指向本仓 `deploy/nginx/companion.conf`，并加装前备份 + 校验失败回滚。
  2. systemd 单元的 `WorkingDirectory` 指向 `chat-platform/backend`，而 `backend/` 在
     G1 物理拆分后已不存在 —— 后端自 G1 起就没起来过，按 `Restart=always` 每 5s
     无声重试。已改指仓库根。
  3. 单元只读 `/etc/companion/.env`（只有 DEEPSEEK），**没读跨仓共享密钥**，于是 8081
     用 yml dev 默认值签 JWT、8091 用随机共享密钥验签 → 跨服务全线 403。已加
     `EnvironmentFile=-/etc/luxera/shared-secrets.env`。
  4. `check-frontend.sh` 拿 `/api/health` 探 8091，而 8091 **没有**这个映射 —— 会把
     正常运行的 8091 判成"没起"，然后去起第二个实例撞端口。已改为"有 HTTP 响应即活着"。
     同脚本还有 `( cd X && java … & )` 的子 shell-PID 陷阱（$! 是子 shell 的 pid，
     cleanup 杀不到 java，留下孤儿占端口），已按仓 2 的做法改成 `exec` + `kill_tree`。
  详见仓 2 README §8.1–8.3（共享密钥、8091 无健康端点、内存封顶）。

### G8 已完成（2026-09-16）—— 前端单入口，伴侣域改由 8081 服务端转发

**用户对 G5 的架构纠正**：*"为什么聊天平台前端会调用到仿真 agent 平台的接口？我让你
聊天平台调用仿真 agent 平台是指后端调用，聊天平台的前端肯定只能调用聊天平台后端啊"*
—— 以及域名侧：*"聊天平台应该是一个域名，然后仿真 agent 自己一个域名，然后其 openapi
放在其域名下"*。G5 让浏览器直连两个后端（`route()` 加 `/agent` 前缀 + nginx/vite 两条
通道），方向从一开始就是错的：它把内网拓扑铺进了前端，前端还要维护一张"哪条路径属于谁"
的表。

**现在的分工**（`CompanionDomainProxyController`，新增）：

- 浏览器只认 `companion.luxera.top` 一个域名，`/api/**` 全进 8081
- 伴侣域请求（8081 没实现的那些）由 8081 在**服务端**用 JDK `HttpURLConnection`
  流式转给 8091，响应原样回传（不缓冲响应头，否则 SSE 变成"等想完一次性吐出"）
- **转发带的是调用方自己的 JWT，不是 HMAC 服务身份** —— 用服务身份会让 8081 变成
  绕过 8091 归属校验的 confused deputy
- 判归属的依据是 **Spring 的 HandlerMapping 优先级**（"8081 到底实现了什么"），
  不是路径段：8081 自己实现的端点有更精确的映射，赢过 `/api/companions/**` 兜底

**顺带查实 G5 的一个真实功能缺陷**：段规则把整个 `conversations` 段判给 8081，而
`POST .../conversations/first` 与 `POST .../conversations/{cid}/chat` 都是 8091 的端点
—— 主链路 404（实测 8081→404、8091→500 证实）。G5 那 36 条 `route()` 断言没拦住，
**因为它们的判据就是那张错表本身**：覆盖了 `conversations/{cid}/messages`（确实属 8081），
却从没覆盖 `first` 和 `chat`。一个错的判据可以被一千条断言钉得死死的。

**验收**：
- `CompanionDomainRoutingTest` **30 条**用例断言**哪个处理器**接走（不依赖下游服务，
  只钉路由规则），前两条就是上述回归点
- `client.route.test.ts`（36 断言）→ `client.single-origin.test.ts`（3 断言）：不再导出
  `route`、源码无 `/agent` 字面量、URL 面就是字面意思
- `scripts/check-frontend.sh` 重写为 F1–F5 单入口验收 → **全绿**（含经 8081 建伴侣、
  读记忆、`/conversations/first` 非 404，以及 `/agent/api/**` 已不是 API 路径）
- 仓 1 测试 **474 → 477**（新增 `CompanionDomainRoutingTest` 3 条 + `InternalWorldTimeParamTest`
  3 条，见 §两个缺陷），前端 27 测试 + tsc 干净

**同轮修掉的两个缺陷**（都是验证 G8 时发现的，与 G8 本身无关但被它暴露）：

1. **`/internal/world` 的时间参数解析**（`InternalChatWorldController`）—— 四个
   `@RequestParam LocalDateTime` 都没带 `@DateTimeFormat`，走的是 Spring Boot 的**本地化**
   默认格式器（不是 ISO），而仓 2 用 `ISO_LOCAL_DATE_TIME` 发串。于是每一个带 since/until
   的服务间读请求都在 8081 抛 `DateTimeParseException` → 500，仓 2 只记一条 WARN 就降级：
   不报错、不缺页，只是数字人读不到用户历史消息，**静默变笨**。四参数一起加
   `@DateTimeFormat(iso = DATE_TIME)`，`InternalWorldTimeParamTest` 钉住调用方真正发出的
   那种串（9 位/8 位/整秒三种）。已真机验证：四条路径全部 200，日志零复现。
   排查弯路：一度误判成"纳秒位数问题"，但单独 `LocalDateTime.parse` 那个串是成功的 ——
   **问题不在值，在转换器选错了格式器**；写成测试才定死（连整秒无小数位也是 500）。
2. **`deploy.sh` 以 root 构建** —— 脚本整体要 root，但 `sudo bash scripts/deploy.sh` 会让
   Gradle 以 root 写 `build/`，class 文件变 `root:root`，之后以 ubuntu 跑任何 gradle 任务
   都死在 `Unable to delete directory ... Permission denied`，且 Gradle 把原因包装成
   "本地构建缓存条目损坏"，与真正的权限问题毫不相干。已改为按 `$SUDO_USER` 构建;
   仓 2 的 `deploy.sh` 同一个坑（`npm run build`）一并加上。

### 构建口径（G1 起）

```bash
cd chat-platform
bash scripts/check-v10.sh                 # 边界守卫（Gradle 版）
~/tools/gradle/gradle-8.14.3/bin/gradle test        # 477 测试（G8 后）
~/tools/gradle/gradle-8.14.3/bin/gradle :chat:bootJar   # chat-platform-1.0.0.jar
LAP_MCP_SERVICE_KEY=<key> java -jar chat/build/libs/chat-platform-1.0.0.jar
CHECK_MODE=split bash scripts/check.sh             # 聊天侧
LAP_AGENT_MODE=split LAP_LOG=<log> bash scripts/check-lap.sh   # 平台侧
bash scripts/check-remote-app.sh && bash scripts/check-ecosystem.sh

cd frontend && npx tsc --noEmit && npx vitest run   # 27 测试
bash scripts/check-frontend.sh                      # G8 单入口端到端验收（需两服务同起）
sudo bash scripts/deploy.sh                         # 部署（构建自动降权到 $SUDO_USER）
```

> ⚠️ `deploy.sh` 必须 `sudo` 跑（要写 `/etc/nginx`、`/var/www`、重启 systemd），
> 但**构建步骤会自动以 `$SUDO_USER` 执行** —— 不要让 Gradle/npm 以 root 写 `build/`
> 与 `dist/`，否则后续以 ubuntu 跑任何构建任务都会 `Permission denied`（见 §G8 缺陷 2）。
```

---

## LAP · 应用平台与生态（2026-09，R1–R15 全部完成）

> **本轮依据**：《LAP v1 — Application Platform Final Architecture》。四层协议
> （Chat Platform Protocol / Application Protocol / Application Manifest / Adapter-Transport），
> Manifest 是中枢，Capability→Action 分层，Resource 是统一读模型，
> **真人与 Agent 共用同一个 `ApplicationGateway`（不存在独立的 Agent API）**，MCP 只是适配器之一。

### 为什么要把应用平台拆出去

拆分前应用平台长在 `digital-human-platform` 内部，有三个结构性问题：

1. **Manifest 是装饰品** —— 写进表后从不解析，且挂在 Application 上而不是 ApplicationVersion 上，
   于是"同一个应用的两个版本"在数据模型里根本表达不出来；
2. **Agent 与应用硬耦合** —— `AgentRuntime` 直接 import `TicTacToeApplicationAdapter.ACTION_MAKE_MOVE`，
   自己解析棋盘 JSON、自己拼去重 key、自己判断 `turn == "O"`。**加第二个游戏必须改 `AgentRuntime`**；
3. **没有"操作"的抽象** —— `/api/v10/games/tictactoe/*` 从请求体里手取 `userId`/`companionId`，
   忽略已认证身份；`Idempotency-Key` 收下就丢。

### 已完成（R1–R15）

| 轮 | 内容 | 证据 |
|---|---|---|
| **R1** | `contracts` 增 LAP 词汇（`ActionRequest` / `ActionResponse` / `ActionSpec` / `ResourceView` / `ApplicationEvent` / `PrincipalType` / `PermissionLevel` / `RiskLevel` / `AttentionPolicy` / `CapabilityView` / `InvocationContext` / …）+ 两个 SPI 端口 | `ActionResponseJsonTest`（全字段 round-trip） |
| **R2** | DH 泛化第一步：`EventRouter` 加 `subscribe`（`register` 保持覆盖语义）；新增 `AgentApplicationFlow`（过滤 → 读 Resource → 问能做什么 → 执行 + 记现实账本）；`DhApplicationEventSink` 永久留在 DH；`AgentRuntime` 删掉 `onApplicationEvent` / `parseBoardState` / `evaluateAndDecideMove` / `boardToString` | `AgentApplicationFlowTest`（mock LLM ⇒ **零次 execute**）、`EventRouterFanOutTest` |
| **R3** | 抽出第 6 个 Maven 模块 `application-platform`（`com.luxera.companion.application`）；DH 的 `digitalhuman/application/**` 21 个主文件 + 5 个测试全部删除；`/api/v10` 控制器原样搬走（前端不破）；边界守卫扩展到三方 | `check-v10.sh`（41 个顶层包分属 5 个所有权模块，10 对引用 + 10 对 pom 全过）、`LapEndToEndTest`、`DhReactsToApplicationEventTest` |
| **R4** | **LAP 面 + 真人应用页，删 `/api/v10`**：manifest 类型/解析/校验/注册（`ManifestValidator` / `ManifestRegistrar`，发布时校验每个 action 都能解析到该版本的 handler）；`ActionGateway` + `POST /api/v1/actions:execute`（真幂等 + 两段式事务 + `ActionInvocationReaperJob` 崩溃恢复 + `expectedResourceVersion` CAS）；`/api/v1/{capabilities,applications,resources,sessions,subscriptions}`；安装/会话/订阅与四条归属不变量；`application-platform` 的**全部 12 张表**；前端「应用」页打**同一个** execute 端点 | `check-lap.sh`（断言 1–9、13 全过，11/12/14/15 待轮次）、application-platform **17 → 153 测试**、`npm run build`、`curl /api/v10/applications` → 404 |
| **R5** | **参考应用：五子棋 + 提醒/日程**（含 DH 提醒只读改造）：`com.luxera.gomoku`（`game.play` 第二候选，action id 与井字棋相同、URI scheme 不同）；`com.luxera.reminder`（`reminder.manage`，`backing: APP_OWNED` + `ReminderResourceProjector` + `ReminderDispatchJob`）；DH 侧 `ReminderService` 改为读 Resource / 写 action，`ReminderRepository` 与 `@Entity Reminder` 删除，`Reminder` 降级为 DTO；新增 `ApplicationNotificationBridge`（`notify` 块 → `companion_notifications`）；`ProactiveEngine` 的提醒循环删除 | `check-lap.sh` 断言 2/3/9b/10 由 skip 转正（全绿，5 项待轮次）；`check.sh` 新增 16 条提醒契约断言全绿（含"旧 `reminders` 表一行没多"）；DH **229 → 253 测试**、application-platform **153 → 222**；三条禁止项逐条 grep 通过 |
| **R6** | **MCP 适配器**：`POST /mcp`（JSON-RPC 2.0，协议 `2025-06-18`）实现 `initialize` / `notifications/*` / `ping` / `tools/list` / `tools/call`，`DELETE /mcp` 关会话；`McpToolCatalog` 把动作投影成工具（描述 = `agentHint` + 资源模板，schema = 动作 schema + 平台保留的 `target`）；工具名撞车时整个目录退化到全名；`McpPrincipalResolver`（`X-Mcp-Principal` + 服务密钥，**密钥留空即 MCP 关闭**）；`SecurityConfig` 放行 `/mcp`（MCP 客户端没有 JWT，身份由适配器自己验） | `McpProtocolTest` **19 条**（含"整条 MCP 往返不创建 `ApplicationSession`"）；`check-lap.sh` **断言 14 由 skip 转正** —— 真人经 REST 落子后，MCP 客户端在**同一行** resource 上应手；`McpEndpointSecurityTest`（过滤器链可达性）|
| **R7** | **Agent 的 capability→action LLM 契约**：`AgentApplicationFlow` 长出**能力选择**与**应用选择**（`route()` = 意图 → 能力 → 应用，逐级收窄；门槛 `app.lap.capability-threshold`，默认 0.6）；动作选择改为**点名**（`pickAction`：在候选里挑一个；只有唯一候选时才允许不点名；编造的动作 id 一律不行动）；`LlmRouter` 三处修正（未知 task 原样通过 / 调用方给的 model 优先 / metadata 透传）；`application.yml` 加 `app.lap.capability-threshold` 与 `app.llm.purpose.application`；`ReminderPlanner` 成为 `route()` 的生产调用方 —— "这句话该不该动用应用"从此由平台回答，不由适配器自己猜 | `AgentApplicationFlowTest` **9 → 21 条**、新增 `LlmRouterPurposeTest` **7 条**、新增 `DhApplicationKnowledgeArchitectureTest` **3 条**（DH 源码里不许再出现任何具体应用的知识）、`check-lap.sh` 断言 11 从"跳过"改为**双模式断言**（见下）|
| **R8** | **生命周期状态机 + REMOTE + 收尾**：`ApplicationStatus` 上的十态迁移表（`canMoveTo` / `legalSuccessorsOf`）+ `ApplicationLifecycleService` + `PATCH /api/v1/applications/{id}/status`（**只有 `SYSTEM`/`APPLICATION` 推得动**，真人 403）；应用与版本行状态**一起**推进，于是发现链真的会因挂起而收敛；`RemoteApplicationRegistrar` + `RemoteApplicationInvoker`（**每个 action 各挂一个转发 handler**、HMAC-SHA256 over `timestamp + "." + body`、转发**派生**幂等键、硬超时、HTTP → `ActionStatus` 同一套映射、`authRef` 是名字不是密钥）；`OutboxRelay` + `ApplicationOutboxRelayJob`（让 subscription 的 `INBOX` 模式成真，主键是事件的确定函数，至少一次投递、失败转 `DEAD` 不静默丢弃）；`SessionReaperJob`（7 天空闲会话**只结束不删除**）；`lap-drop-legacy.sh` DROP 四张遗留表 | 新增 `LifecycleStateMachineTest` **8 条**（含 `theVersionRowsMoveWithTheApplication`，它在实现里抓出一个真 bug —— 恢复分支的条件写反，成了死代码）、`VersionImmutabilityTest` **5 条**、`OutboxRelayTest` **7 条**、`SessionReaperTest` **5 条**、`RemoteApplicationInvokerTest` **12 条**（真 `HttpServer`，验签/超时/幂等键派生逐条断言）、`RemoteApplicationRegistrarTest` **7 条**（手工装配，避免污染共享内存注册表）；application-platform **241 → 285 测试**；`check-lap.sh` 断言 1 的"旧表不存在"半边**打开**（逐张断言四张遗留表已删）、新增断言 16（生命周期 + 版本不可变）与断言 17（`INBOX` 真的投出去了：`lap_outbox` 落行 → `status='DELIVERED'` → `last_delivered_at` 非空）|

| **R9** | **Session 重构：删 Installation。** `installation` / `permission_grant` 两张表 DROP（`scripts/lap-v2-reset.sh`），`ApplicationSession` 从"一个 principal 的实例"改成"一个多人实例"（`owner_principal_*` / `visibility` / `join_policy` / `min/max_participants` / `conversation_id` 进场，`installation_id` 与 `principal_*` 离场）；新增 `application_session_participant` + `session_permission`；`ApplicationSessionStateMachine`（五态）；`PermissionEvaluator` 从"查 installation"改成"查 participant"（`NOT_INSTALLED` → `NOT_A_PARTICIPANT`，`INSTALLATION_INACTIVE` → `PARTICIPANT_INACTIVE`）；`PrincipalType` 加 `EXTERNAL_AGENT`；`ActionGateway` 会话解析五档（新增"该 principal 最近的 ACTIVE 会话"与 `ensureSession`）；`ensureInstalled` → `ensureSession`；`AgentRouteResolver` 改从 participant 推；`action_invocation` 的唯一键加 `session_id`；DH 只改 `ReminderService` 一行 | `PermissionEvaluatorTest` **11 条逐条改写而非删除**、`ParticipantTest`、`SessionStateMachineTest`、`ApplicationSessionOwnershipTest`、`ResourceStoreTest` 新增"提醒收件箱没有会话可挂"专条；`check-lap.sh` 断言 1/9*/10/11/14/15 改写（断言 1 扩成"新列在、旧列不在"的反向断言） |
| **R10** | **Participant + Invitation。** `session_invitation` + 邀请状态机（State Pattern）；token 铸造/哈希/校验/消费/收回（**库里只有 SHA-256，明文只在创建响应里出现一次**）；`LapParticipantController`（join / 名单 / 自己走）+ `LapInvitationController`（铸票 / 列表 / 收回 / 公开兑票）；`APPLICATION_INVITATION` 平台事件（由邀请服务发射，绕过 manifest 的 `triggersAgent` 闸门 —— 那道闸门是防**应用**的，不是防平台的）；前端分享链接 | `InviteCreateTest`、`InviteConsumeTest`、`InviteExpireTest`、`InviteRevokeTest`、`SessionJoinTest`、`SessionLeaveTest`；断言 token 明文不进库 |
| **R11** | **Application Launch + Surface。** manifest 第 8 个 section `ui`（`type` / `entry` / `minClientVersion` / `surfaces[]`）+ 校验器（`UI_TYPE_REQUIRED` / `REMOTE_UI_ENTRY_NOT_ABSOLUTE` / `UI_SURFACE_MODE_CONFLICT` / `DUPLICATE_SURFACE` …）；`Availability` 投影（§4.1 那张表）+ 开会话闸门（不可用 → 409 `STATE_CONFLICT`）；`POST /applications/{id}/sessions` 改成 §16 形状（嵌套 `application` / `participant`，删掉平铺的 `ownerPrincipal*`）；新增 `GET /applications/{id}` 详情（十态 status + 三列布尔 + `ui` 段）；前端**应用市场 / 应用详情 / Session 页 / 分享链接加入页**四页 + `SurfaceHost` 五态全量 + EMBEDDED 登记表 + REMOTE iframe | `ManifestValidatorTest` 43 条（含 `ui` 段全套拒绝用例）、`AvailabilityProjectionTest` **13 条**（§4.1 逐行 + 投影全覆盖 + 闸门串成一条链）、`LapWebSurfaceTest` 新增详情页两条；前端 `SurfaceHost.test.tsx` **15 条**（vitest，读**后端那份真的 manifest**）；`check-lap.sh` 新增断言 18（§16 形状 / 详情三列 / 五条 Surface / 404）；`npm run build` |
| **R12** | **Chat / Application 深度集成。** 新契约包 `contracts/chat/`（6 个 DTO `ApplicationCard` / `ApplicationLaunchRequest` / `ParticipantView` / `ApplicationSessionView` / `ApplicationLaunchResponse` / `ApplicationInvitation` + `ApplicationCatalogException` + `package-info`）与第 3 个 SPI 端口 **`ApplicationCatalogPort`**（`cards` / `card` / `sessionsOfConversation` / `session` / `launch` / `invite`）；平台侧实现 `ApplicationCatalogAdapter`（把 `SessionException` 翻成跨模块的 `ApplicationCatalogException`，保住 `ActionStatus`）；`ApplicationSessionService.ofConversation()` / `applyLaunchOptions()` + 仓储 `findByConversationId`；chat-platform 加**三个会话上下文端点** `GET/POST /api/companions/{c}/conversations/{v}/applications` 与 `POST …/{sessionId}/share`；**应用卡片就是一条消息**（`messageKind=APPLICATION_CARD` / `APPLICATION_INVITATION`，metadata 带 applicationId/sessionId/name/role/status，走 `ConversationService.addMessage` **不唤醒数字人**）；`application_session.conversation_id` 落库（§85）；前端 `api/chatApplications.ts` + `ApplicationCardBubble` 两张卡片（进入 / 分享到对话 / 复制链接）+ Chat 侧「一起玩点什么」面板 | `ChatApplicationPortTest` **6 条**（行为证明：整条链路跑在一个本仓库从未见过的应用 `com.example.paper-plane` 上；源码证明：扫集成代码里不出现任何一个真实应用 id/动作名）、`ApplicationCardMessageTest` **4 条**（卡片是一条消息 / 同一条时间线 / 令牌明文进消息 / **被拒时绝不留下说假话的卡片**）、`ModuleBoundaryArchitectureTest` 六条仍绿；前端 `ApplicationCardBubble.test.tsx` **9 条**；`check-lap.sh` 新增**断言 19**（16 条：真实适配器跨端口开应用 → 卡片落 `messages` → 分享落消息而明文不进库 → 未知应用 404 `UNKNOWN_APPLICATION`） |
| **R13** | **Agent Participation —— 数字人成为一种用户。** `ApplicationRuntimePort` 加四个方法（`sessionsOf` / `joinByInvitation` / `joinSession` / `leaveSession`）+ `SessionRef` 记录；DH 侧新增 **`SessionResolver`**（定位四策略：显式 context > 资源行上的 sessionId > 平台说"我在这一场里" > 可发现的开着门的场；`locate` **只读**、`enter` 才进场，`enter` 遇到"点名了一场进不去的"**抛而不是另开一场**）；`CapabilityResolver` / `ApplicationResolver` / `ActionSelector` 从 `AgentApplicationFlow` 拆出；`AgentApplicationFlow` 改成**八段 Pipeline**（READ → LOCATE → CONTEXT → PENDING → ELIGIBLE → DECIDE → EXECUTE → RECORD）+ `PipelineReport`（停在哪一段、为什么、用的是哪条策略）；新增 **`AgentApplicationInvitationHandler`**（accept / reject / **ignore** 三态，兑票走 `joinByInvitation` —— 与真人点开 `/join/{token}` 逐字相同的门）；`RealityEventType` 加 `APPLICATION_INVITATION_ACCEPTED` / `APPLICATION_INVITATION_DECLINED` | `AgentApplicationFlowTest` **21 条一条不改**（R2/R7 留下的保险丝原样通过）、新增 `SessionResolverStrategyTest` **13 条**、`AgentApplicationInvitationTest` **12 条**（含"明文票一个字都不许进账本"与"LLM 不可用 ⇒ 没决定 ⇒ 不记账"）、`AgentApplicationPipelineTest` **9 条**（每段各自的停机理由 + "定位不到会话不是失败"）、`DhApplicationKnowledgeArchitectureTest` 仍绿；DH **275 → 309 测试**；`check-lap.sh` 新增**断言 20**（定向邀请 → 平台事件通道 → 单向门 → 数字人邮箱；含"收件人此刻还不在这一场里"的前置断言，证明邀请不走参与者名单） |
| **R14** | **第三方生态 —— 一个 LAP 应用可以活在平台进程之外。** 三个入口同时打开：**① Developer API**（`developer` 表，一个真人多个开发者身份；`POST /api/v1/developers` 幂等、`POST /developers/{id}/applications` **认领**应用 id —— 应用 id 就是 manifest 的反向域名身份，第二个认领者 409 `APPLICATION_TAKEN`；`requireOwned` 归属闸门 + `NOT_YOUR_APPLICATION`；挂起开发者 = 吊销钥匙不删数据）；**② 双 SDK**（Python `sdk/python/luxera_application` 零依赖纯标准库：`LapServer` + `@action` 装饰器 + `verify_request` 三道闸（缺头/时窗/常数时间比较）+ `IdempotencyStore`（**失败也缓存**）+ `LAP_SERVICE_SECRET`；TS `sdk/typescript` 用 node:crypto，与 Java 平台、Python SDK **逐字节同一套 HMAC**：`sha256=` + hex(timestamp + "." + body)，300 秒重放窗，`timingSafeEqual`）；**③ 参考远端 `remote-apps/gomoku`**（纯标准库 Python 五子棋，动作/错误码与内置同名同义，`LAP_SERVICE_SECRET` 缺失时 503 `REMOTE_NOT_CONFIGURED` 而不是裸跑） | `DeveloperApiTest` **12 条**、`RemoteRegistrationTest` **8 条**（含"签名与独立实现逐字节一致"—— 测试里用裸 Mac/SecretKeySpec 另算一遍，不是拿 `RemoteSignature.sign` 自证；远端挂了回 `REMOTE_UNAVAILABLE/FAILED` 而非异常）、TS SDK `node --test` **4 条**、`check-remote-app.sh` **R1–R6 全绿**（R1 同能力两个实现并存于发现链；R2 从零到应用行 + 重认领 409；R3 一步棋跨 Java→HTTP→HMAC→Python 四层**且远端状态投影成 resource 行读得回**；R4 同键重发手数不变 —— 四层幂等；R5 远端 409 → `REMOTE_CONFLICT` + 远端原话带回；R6 manifest 无密钥）；两个由 E2E 抓出的真修复：`SecurityConfig` 放行三个开发者端点（过滤器层 403，控制器与单测都看不见）、`UNKNOWN_DEVELOPER` 进 `SessionException` 状态表（default 掉到 400，而"查无此人"是 404） |
| **R15** | **生态收官 —— 架守护栏 + 整链验收。** 两个交付物：**① `LapEcosystemArchitectureTest`**（bootstrap-app，把 §115–§118 的四条架构不变量从文档变成会红的规则：聊天平台不许知道任何内置应用（§115）；动作执行永远经 `ActionGateway` —— web 层不许直接碰 Handler/Registry/Resolver 那一族，正面还断言 `LapActionController` 依赖 `ActionGateway`（§116）；对 `ResourceRepository` 的写只有 resource 与 repository 两个包（§117）；`AgentRuntime` 对应用平台的全部依赖被禁 —— Agent 只经 Port 或 MCP 进来（§118）。规则的**非空性是注入探针证过的**：往 web 与 chat 各写一个违规类，四条规则各自炸出真违规再删掉变绿 —— 一条从不失败的守卫等于没有守卫）；**② `scripts/check-ecosystem.sh`**（§126 那条完整链一条脚本走完：远端五子棋 + 带 `LAP_REMOTE_APPLICATIONS` 的 jar 一起起，E1 发现链上内置与远端并存；E2 Human A 开局落子天元；E3 铸票兑票（幂等不烧名额）；E4 定向邀请 Agent + MCP 落子；E5 同一行 resource 上 `board[112]=X`(Human) / `board[7]=O`(Agent 经 MCP)、手数=2、参与者行数与兑票幂等一致；E6 能力→应用→动作三级接口面 + `reminder.manage` 候选里没有游戏（能力隔离）；E7 审计账本上 HUMAN 与 AGENT 各有行动 —— 同一条审计链） | `LapEcosystemArchitectureTest` **4 条**（探针注入 → 真违规 → 清掉 → 绿）、`ParticipantTest` **24 → 25 条**（新钉"场主兑自己的 MEMBER 票不丢所有权"）；`check-ecosystem.sh` **E1–E7 全绿**；又一个由 E2E 抓出的真缺陷：`ParticipantService.join` 对**已在场者**也会拿邀请的 role 覆盖原 role —— 场主先兑自己的分享链接（幂等）就被降成 MEMBER，随后的定向邀请回 `NOT_SESSION_OWNER`。既有幂等单测只数行数，行数没变、所有权悄悄没了。修法：已在场者跳过角色重置，幂等只补授权与状态 |

**R9 的关键决定**（删掉 Installation 之后，会话必须总是存在）：

1. **"装"这个概念从代码里彻底消失，而不是换个名字。** 删除的理由不是"少两张表好看"：
   `Installation` 是 v1 整条归属链的根（`Application → Installation → Session → Resource`），
   于是"用一下提醒应用"= "先装它"、"数字人能下棋" = "数据库里有一行 AGENT 的 installation"。
   v2 的产品模型是微信小程序：**打开就是开一场会话**，而会话可以同时坐着好几个人。
   `check-lap.sh` 有一条断言直接 `curl POST /applications/{id}/install` 要 **404** ——
   加回任何一个"安装"入口，它立刻红。
2. **`ensureInstalled(appId, ctx)` → `ensureSession(appId, ctx) → sessionId`。**
   这是整次重构的拱心石，也是 DH 侧唯一要改的一行（`ReminderService`）。语义上它更诚实：
   删掉 installation 之后，"我允许这个应用为我做事"不再是平台概念，剩下的是
   "**我在这个应用里有一个正在进行的实例**" —— 那正是 Session。
3. **会话必须总是存在，所以资源解析多了一档兜底。** `ActionGateway` 第 3 步"会话解析"的优先级
   从四档扩成五档：`显式 context.sessionId > 已存在资源行的 sessionId > URI 模板里的 {sessionId} 段
   > ★该 principal 在这个应用下最近的 ACTIVE 会话★ > 都没有 → ensureSession 新建一个(OWNER)`。
   第 4 档是**删掉 installation 之后唯一会"静默失效"的地方**：`reminder://owner/{userId}` 的模板里
   没有 `{sessionId}` 段，前三档全都匹配不上。没有它，DH 的每一次提醒调用都会退化成"新建一个会话"，
   `application_session` 会被闲聊级调用灌满；没有第 5 档，"打开应用即用"的体验就不成立。
4. **`AgentRouteResolver` 是最容易漏的一处，因为它不在 `permission/` 里。** 它住在 `event/`，
   删表的编译错误不会指着它，改完权限测试也全绿 —— 但事件路由会**静默地一个人也不唤醒**。
   所以它单列一条决定：从事件所属会话的 `application_session_participant` 里找
   `principal_type='AGENT' AND status='ACTIVE'` 的参与者，而不是去 `installation` 表里找。
5. **`SUSPENDED` 是"下架"不是"作废"，这一条在 v2 里有数据模型撑腰。** 挂起只影响**新会话**
   （`allowsNewSession=false`），已经在进行的那一局照常读得出来 —— 见 §4.1 那张表与
   `AvailabilityProjectionTest.suspendingAnApplicationRefusesNewSessionsButKeepsTheExistingOne`。
   一局下到一半的棋因为运营点了一下"挂起"而当场作废，是平台在惩罚用户承担运营的后果，
   而用户什么都没做错。

**R10 的关键决定**（邀请是"把别人请进来"的唯一方式）：

1. **join 的请求体里只有 `role`。** 方案 §12/§31 的请求体带着 `principalType` + `principalId`，
   与 §36「身份从 Context 获得」直接冲突，而且是一个越权入口 —— 谁都能替别人报名。替**别人**加入
   只有一条路：邀请链接。`principalType` / `principalId` 一律取自已认证身份。
2. **链接里那 30 个字符不是 id，是 Capability Token 的明文。** 库里只存 SHA-256，于是拿到链接的人
   能进这一场，但看不到这张票编号几号、谁铸的、给谁。明文在**创建响应里出现且只出现一次** ——
   丢了只能重铸一张。这条性质由 `InviteCreateTest` 断言（库里查不到明文）。
3. **`APPLICATION_INVITATION` 是平台事件，不是应用事件。** 方案 §53 说"事件不能表达
   AgentShouldXxx"，§60 又说要有 `APPLICATION_INVITATION` —— 两者不冲突：邀请事件说的是
   "有人邀请你"，不是"你必须来"。但它**由邀请服务发射**，绕过 manifest 的 `triggersAgent` 闸门：
   那道闸门是防**应用**的（应用不该知道谁是 Agent），不是防平台的。
4. **`INVITE_ONLY` 是默认姿态，不是"还没实现邀请"。** 一个会话默认不该是任何人都能进来的；
   开放（或发行邀请链接）是会话主人的显式决定。所以 `POST /sessions/{id}/participants` 对
   `INVITE_ONLY` 的会话只有开局的人走得通 —— 别人会拿到 `SESSION_INVITE_ONLY`，那不是错误。

**R11 的关键决定**（平台不定义 UI 渲染协议）：

1. **平台只声明三件事，一个字都不多。** §68 给的是 `surface type` / `entry` / `minClientVersion`；
   §69 说清了为什么 —— 一旦开始定义 button/color/layout/font/component，最终会重新造一个
   Flutter。这条边界在代码里是**机械的**：`ManifestParser` 拒绝 `ui` 与 `surfaces` 里任何不认识的
   键（`UI_UNSUPPORTED_KEY`），而不是静默忽略 —— 静默忽略会让平台一点一点长出 UI 参数，
   而每一步看起来都只是"多支持一个字段"。
2. **`entry` 是模板，客户端做且只做替换。** 变量只有 `{applicationId}` 与 `{sessionId}` 两个 ——
   平台唯一确定知道的就是这两件事。认不出的变量**原样留着**，不抹成空串：抹掉会得到一条看起来正常
   的路径，直到 404 白屏才暴露。
3. **五种 Surface 是五种交互契约，不是五个 CSS 类。** `FULL_PAGE` 铺满有返回、`EMBEDDED` 嵌在
   别人页面里（所以**没有**关闭按钮 —— 关掉它不归它管）、`MODAL` 有遮罩能关、`PANEL` 是贴边抽屉
   不吃遮罩、`INLINE` 是一行且带升级为整页的入口。`SurfaceHost.test.tsx` 断言的就是这些**行为差别**；
   一个只测样式的测试改个 CSS 就红一片，最终会被人删掉。
4. **呈现方式归平台，界面内容归应用。** 前端是一个 `SurfaceHost` + 一张内置应用登记表；
   登记表按 **applicationId** 分键，不按 surface 分 —— 一个应用只有**一份**界面实现，五种 Surface
   是这同一份界面的五种**摆法**。按 surface 分键的话，作者就要为 MODAL 再写一遍棋盘，
   而两份棋盘很快就会不一样。
5. **`ui` 是可选的，默认值现算不烘进解析结果。** `reminder`（一个面向 Agent 的应用）的清单里
   没有 `ui` 段 —— 平台在 `SurfaceCatalogue` 里给它一个默认（EMBEDDED + 一条 FULL_PAGE）。
   不把默认值烘进 `ApplicationManifest`，"作者没写"与"作者写了默认值"在版本 diff 里才分得开。
6. **应用详情页在应用下架之后照样打得开。** 详情接口刻意不按 `inMarket` 过滤成 404：
   一个被挂起的应用需要能说出一句"它已下架"，而 404 只会让人以为是自己把 id 打错了。
   真正被拒的是"开一局新的"，而那个拒绝带着 `APPLICATION_NOT_AVAILABLE` 与 409 —— 
   "被下架了"和"没有这个应用"是两件事。

**R12 的关键决定**（聊天侧看见应用，但一个应用都不认识）：

1. **`chat-platform` 到现在也没有依赖 `application-platform`，这一轮一个字都没松。** 设计方案的
   §63 要求聊天里能发现/启动/邀请应用，§115 又要求它不 import 具体应用，而既有守卫更强：
   `check-v10.sh` 的 pom 禁令与 `ModuleBoundaryArchitectureTest` 直接不许这两个模块互相看见。
   三者同时成立的唯一办法是多一个**契约端口**：
   `contracts/spi/ApplicationCatalogPort` ← 实现在应用平台的 `ApplicationCatalogAdapter`，
   chat 只调接口。放宽边界换功能是这次重构里最贵的偷懒 —— 一旦 chat 能 import 应用平台，
   下一个功能就会直接去读它的表。
2. **"聊天侧不认识任何具体应用"有两种证法，而它们证的**不是**同一件事，所以两条都要。**
   *行为证明* 让整条链路跑在一个本仓库从未见过的应用（`com.example.paper-plane`）上 ——
   它证明了"不认识也能跑"，但证明不了"没有偷偷认识一个"；*源码证明* 扫集成代码，断言里面不出现
   `tictactoe` / `game.make_move` 这类词 —— 它证明了"没有偷偷认识"，但证明不了"不认识也能跑"。
   一条的漏洞正好是另一条的强项。这是 §63 被认真对待的样子：只写一句注释说"我们不认识具体应用"，
   是没有任何东西在守的。
3. **注释不算。** 那条源码扫描会先把 `/* … */` 与 `// …` 剥掉再匹配。因为这条规则管的是代码
   **认识**什么，而一段说"这个类不认识井字棋"的注释是一句*关于*应用的陈述。把注释也算进去，
   规则会逼着人把话说含糊 —— 而它要防的东西（一个写死的 `applicationId`）一个都不会因此消失。
4. **卡片是一条消息，不是第二种东西。** `messages` 表本来就有 `message_kind` 与 `metadata` 两列，
   它们就是为这种"长得像消息、内容不是一句话"的东西准备的。另建一张 `application_card` 表会立刻
   带来一个没有答案的问题：卡片和消息谁先出现？分页怎么合？已读状态算谁的？
   所以 `ApplicationCardMessageTest` 钉的正是"它没有变成第二种东西" —— 有 senderType、有
   messageKind、和别的消息排在同一条时间线上、也进 `messageCount`。
5. **落卡片消息走 `addMessage` 而不是 `MessageCoreService.send`，因为平台通告不唤醒数字人。**
   后者会在事务提交后唤醒数字人平台 —— 那是对的，一条用户消息值得它看一眼。而"井字棋已开启"
   是系统文本，把它喂给 LLM 只会让数字人对着它生成一句自己的回复。数字人要知道这一局开起来了，
   走的是应用事件那条路（§60 的 `APPLICATION_*` 家族），那条路说的事情比这行字准确得多。
6. **先开应用、再落消息。** 顺序不是随手写的：反过来的话，一次被拒的启动（应用恰好被下架）
   会先在对话里留下一条"已开启"的卡片，然后才失败 —— 一条说假话的历史，事后只能靠人删。
   `ApplicationCardMessageTest.开应用被拒时绝不留下一条说假话的卡片` 断言的就是这个顺序。
7. **`ApplicationCatalogException` 住在 `contracts.chat` 里，而不是让 chat 接住 `SessionException`。**
   接住它就等于 chat 要编译应用平台的类型（边界破了）；而另造一套并行状态词表会让同一件事有两个
   名字。所以跨模块那一层只做一件事：把 `SessionException` 的 code / message / `ActionStatus`
   原样搬进一个双方都认识的壳里。聊天侧再翻成 HTTP 时有一份**刻意写下来的、四行的**状态映射重复
   —— 它值得被明确承认，而不是假装没有：一份不被承认的重复会慢慢长歪，一份被承认的重复至少会有
   人在改其中一份时想起来看看另一份。
8. **`conversationId` 在路径上，不在请求体里。** 请求体里能写的东西，一个拿到别人 `conversationId`
   的人就能把应用开到别人的对话里；放进路径则把"这一段对话"变成不可绕过的前提。而它同时是
   §85 要求的落库字段（`application_session.conversation_id`）—— 会话行上要记住它属于哪段对话，
   而这件事只有一个地方能做：在启动时传下去。
9. **分享 = 把邀请链接发进对话，不是塞进剪贴板。** 铸出来的票只出现一次（库里只有哈希），
   落成一条消息之后它就再也不会丢：换台设备、刷新页面，那条链接还在对话里，还能再点一次。
   走剪贴板的话，用户没粘贴就是真的没了。
10. **没注册过的应用从"可开列表"里消失，但已经开着的那一场还在。** 这是 §4.1 第二列
    （`allowsNewSession`）与第一列（`inMarket`）的区别在聊天侧的样子 —— 平台不替运营惩罚用户，
    用户什么都没有做错。

**R13 的关键决定**（数字人成为一种用户，而不是一种被安装的插件）：

1. **"打听"与"承诺"必须是两个方法，而不是一个带开关的方法。** `SessionResolver` 拆成
   `locate`（只读，永不 join）与 `enter`（会 join）。这不是洁癖：`locate` 跑在**每一条**事件上，
   而删掉 installation 之后最容易发生的退化就是"反正要找一个会话，顺手就进了"—— 那样每读一次
   资源都会往会话表里塞一场。`SessionResolverStrategyTest.locateNeverJoinsEvenWhenTheDoorIsOpen`
   把这条钉死：门开着也不行。
2. **点名了一场进不去的会话 → 抛，而不是另开一场。** `enter` 的第 3 步是最难写对的一处。
   如果 `ensureSession` 在"我进不去我想进的那一场"时够得着，一次失败就会变成一次**静默的复制**：
   邀请你的人在那场里等着，而你在新的一场里对着空房间，两边都不知道发生了什么。
   所以 `UnavailableException` 带稳定 reason code（`NEEDS_INVITATION` / `SESSION_NOT_VISIBLE`），
   并且断言里同时 `verify(port, never()).ensureSession(...)`。
3. **定位会话是尽力而为的富化，不是闸门。** 八个阶段里 LOCATE 刻意**没有返回值** —— 定位不到
   就往下走。把它做成闸门会有一个当时看不出来的后果：`reminder://owner/{userId}` 的资源行上
   从来没有 `sessionId`（R5 起一直是 NULL），那些应用的事件会从此**一个人也唤不醒**，
   而所有既有断言仍然全绿。`AgentApplicationPipelineTest.withoutASessionThePipelineStillRuns`
   就是这条的守卫。
4. **数字人接受邀请走的是与真人**逐字相同**的那一扇门。** 明文 token 在 `APPLICATION_INVITATION`
   事件的 payload 里 —— 一个数字人没有浏览器可以点开 `/join/{token}`，所以那封信**就是**它的链接。
   它拿票走 `port.joinByInvitation` → `InvitationService.consume`，与人点链接完全同一条路径；
   票不灵了才轮到"门还开着"（`joinSession`）。本仓库里没有、也不能有第二条进场路。
5. **因此明文票在 DH 侧只有一个去处：`joinByInvitation` 的第一个参数。** 不写日志、不写账本、
   不进提示词、不进异常消息。一条凭据一旦被记进"记忆"里就不再是凭据了 —— 而账本是 append-only
   且会被回放、被投影、被读进提示词。这条不靠自觉：`AgentApplicationInvitationTest` 里有一条
   断言逐字检查账本 payload 里没有那 30 个字符。
6. **三个决定，而不是两个：`accept` / `reject` / `ignore`。** `ignore` 是"**没有做出决定**"——
   LLM 不可用、是 mock、或者答得不能采信（没有 `accept` 字段）。它什么都不记。这一档的存在理由
   是把"没决定"与"决定不去"分开：默认"去"会让数字人在服务抖动时到处乱窜，默认"不去"会让它
   替自己撒一次谎 —— 把一次 LLM 超时写成"这个数字人拒绝过谁"，是账本里最难查的一类假话。
7. **邀请事件也是 `APPLICATION_EVENT`，也带着 `agentTrigger`，但它不走反应路径。**
   `ExternalEventType` 只有一个常量，平台事件与普通应用事件在**类型**上是同一个东西，区分它们的
   只有 payload 里的 `eventType`。所以 `AgentApplicationFlow.onApplicationEvent` 在入口处有一行
   早退；否则反应路径会把 `session://invitation/...` 当成一个资源去读，而那个东西根本不存在。
8. **一封没能兑现的邀请信不许改口成"谢绝"。** `accept` 两扇门都没开时：不记 `ACCEPTED`、
   不重试、**也不退回去记一笔 `DECLINED`**。邀请信没兑现是一个事实，而它不是这个数字人的决定 ——
   账本里记错这一笔，事后就分不清"他没去"与"他没进得去"。
9. **邀请事件的收件人由铸造方显式点名，不查参与者名单。** `AgentRouteResolver` 回答的是"这条事件
   挂着的会话里有哪些 AGENT 参与者"—— 而邀请的收件人正是一个**还没进来**的人（它正是被邀请才有
   机会进来的），问"他在不在这场里"答案必然是没有。所以 `data.companionId` 在铸票那一刻就盖上，
   平台直接投给这个人。这不是绕过安全检查：能点名的人只能是会话主人（`InvitationService.mint`
   的 `requireOwned`），而收不收数字人自己决定。`check-lap.sh` 断言 20 里那条"收件人此刻还不在
   这一场里"的前置断言，就是这条决定有没有被实现的判据。

**R14 的关键决定**（一个 LAP 应用可以活在平台进程之外）：

1. **"建应用"= 认领一个 id，不是生成一个 id。** 应用 id 是 manifest 里的反向域名身份
   （`com.example.paper-plane`），它要出现在 URI、日志、LLM 上下文里 —— 它在认领之前就已经
   被决定了。所以 `POST /developers/{id}/applications` 的语义是认领：id 已有人认领就 409
   `APPLICATION_TAKEN`。若这里放行，第二个开发者一个 POST 就能抢走第一个的应用 —— 而任何
   权限模型都还没来得及建立。归属从此有一条唯一链：`developer → application → manifest`。
2. **远端的状态必须投影回平台的一行 resource，否则"共享同一个 Resource"对远端应用失效。**
   内置 handler 自己 `ctx.write(state)`；远端应用拿不到平台写句柄，状态在平台进程之外。
   E2E 第一次跑就抓出了这个缺口：棋下完了 `GET /api/v1/resources` 404。修法是
   `RemoteActionHandler` 在**写动作成功**且远端返回 `state` 时投影一次 —— 远端仍是唯一真相
   （非法落子只有它判得出），平台这一行是它最新一次写入的快照；读动作不投影（读不该让
   资源版本 +1），投影失败不改调用结果（远端已经改完，此刻回 409 等于对调用方说谎）。
3. **manifest 里永远只有 `authRef`，密钥永远在平台配置里。** `authRef` 是名字不是密钥：
   manifest 会进数据库、进日志、进导出包。`LAP_REMOTE_AUTH_<REF>` 缺失时注册直接
   `REMOTE_AUTH_UNRESOLVED` —— 那是配置错，该在启动时炸响，而不是运行时才对一个真实调用方
   说"网络问题"。`check-remote-app.sh` 断言 R6 逐字检查 manifest 里没有那个密钥。
4. **三个实现（Java 平台 / Python SDK / TS SDK）共用同一套 HMAC，且不能互相自证。**
   `sha256=` + hex(hmac-sha256(timestamp + "." + body))，300 秒重放窗，常数时间比较。
   `RemoteRegistrationTest.theSignatureAgreesWithAnIndependentImplementationOfTheSameProtocol`
   在测试里用裸 `Mac`/`SecretKeySpec` **另算一遍**再交给 `RemoteSignature.verify` —— 拿
   `sign()` 自证等于没测。E2E 断言 R3/R4/R5 则证明 Python 那一份真的在同一套协议上。
5. **远端的拒绝由平台按状态分类转述，但一个字都不改。** 409 → `REMOTE_CONFLICT`、
   404 → `REMOTE_NOT_FOUND`、401/403 → `REMOTE_DENIED`：code 前缀点明"这是远端说的"，
   而远端自己的解释原样落进 message（"现在轮到 O, 你执 X"）。少任何一半，排障的人手里
   就只剩一个 409，既不知道是谁拒绝的、也不知道为什么。
6. **`surefire:test` 不重编译，`mvn package` 不重打未变的 bootstrap jar。** R14 里两个
   真缺陷都是这么漏掉的：新加的单测根本没跑（计数没涨）、改了依赖模块后 fat jar 里还是
   旧的。从这轮起：跑测试用 `mvn test`（带编译），打包一律 `mvn clean package`。
   断言 20 在 R13 就因为同类的"旧 jar"翻过车 —— 同一个坑，值得写进 README 两次。
7. **过滤器层的 403 是单元测试与控制器都看不见的故障面。** Developer API 三个端点第一次
   E2E 全 403：`SecurityConfig` 的放行名单里没有它们，请求死在 JWT 过滤器上 —— 控制器写好了、
   单测也绿（单测直接调 service，不过过滤器）。修法是逐条 `antMatchers(method, path)`
   放行并写明理由；发现它的不是测试套件，是 `check-remote-app.sh`。这也是验收脚本存在的
   理由：有些错只有走完整条真实链路才暴露。
8. **`SessionException` 的状态表要认得每一个新 code，default 是 400。** `UNKNOWN_DEVELOPER`
   落在 default 上回了 400 —— 400 在对调用方说"你的载荷写错了"，于是门户会去改请求体，
   而不是换一个 developerId。"查无此人"是 404。单测 `anUnknownDeveloperIsNotFoundNotABadRequest`
   钉住这条；它反过来也说明：**新 code 必须带一个"它该映射成什么状态"的测试**，否则
   default 不会自己开口说话。

**R15 的关键决定**（架构不变量变成会红的规则，整链一次走完）：

1. **一条从不失败的守卫等于没有守卫 —— 所以每条规则都要先证明自己会红。** 四条 ArchUnit
   规则（§115–§118）写完后的验证不是"跑一遍绿"，而是**注入探针**：往 web 包写一个引用
   `RemoteApplicationInvoker` + `ResourceRepository` 的 `ProbeController`、往 chat 包写一个
   引用内置应用的 `GuardProbe`，跑测试看四条规则各自炸出真违规，再删掉探针看它变绿。
   中间还踩了 ArchUnit 1.0.1 的两个坑：`doNotResideInAnyPackage` / `haveAnyOfNames` 不存在
   （用 `resideOutsideOfPackages(String...)` 与 `haveNameMatching(regex)`）；删掉探针源文件
   后规则仍红 —— **target 里的旧 `.class` 还在**，必须 `clean` 才是真的删掉了。
2. **§116 第一版规则阻塞了整个 action 包 —— 17 个违规里多数是合法的。** "web 不许依赖
   action 包"会连 `LapActionController → ActionGateway` 一起禁掉，而 Gateway 恰恰就住在
   action 包里。守卫要守的是"绕过 Gateway 直碰 Handler/Resolver 那一族"，不是"不许进门"。
   修法：按**类名**禁（`haveNameMatching` 那一族 handler 侧类），外加一条正面断言
   （Controller 必须依赖 `ActionGateway`）—— 只禁不证，规则改对了也看不出来。
3. **幂等的第二层：行数没变 ≠ 什么都没变。** E2E 走到"场主给 Agent 发定向邀请"时回
   `NOT_SESSION_OWNER` —— 而场主三分钟前才铸的票。链条是：`ParticipantService.join` 对
   **已在场者**也执行 `setRole(normalizeRole(邀请的role))`，场主兑自己的分享链接（幂等、
   不烧名额、行数不变）时被票上的 MEMBER 盖掉了 OWNER。既有幂等单测 `joiningTwiceIsTheSameAsJoiningOnce`
   只断言行数与 id —— 全绿，所有权悄悄没了。修法：已在场者跳过角色重置；新单测
   `anOwnerRedeemingTheirOwnMemberTicketStaysOwner` 钉住。教训与 R14 决定 7/8 同族：
   **过滤器层、状态表 default、行数不变的角色漂移，都是"单测绿而真链路红"的同一类盲区** ——
   这也是每轮都要跑真进程 E2E 的理由。
4. **MCP 工具名的"全名退化"是保护，不是默认。** 远端五子棋（`com.luxera.remote-gomoku`）与
   内置（`com.luxera.gomoku`）并存，但短名各是 `remote-gomoku` 与 `gomoku`（短名 = id 的
   **最后一段**），census 各为 1 —— **不撞，所以用短名**。第一版脚本想当然写了全名
   `com_luxera_remote_gomoku.game_make_move` 而吃 `TOOL_NOT_FOUND`：全名只在短名真的撞车时
   才出现。"两个五子棋并存"是 §107 的证据，但并存 ≠ 撞车 —— 那条退化规则有自己的单测钉着，
   E2E 这里反而是它的反例面。
5. **EXECUTE 级动作的幂等键在 MCP 面同样必须。** MCP 适配器收两处：请求头 `Idempotency-Key`
   优先，`arguments._idempotencyKey` 兜底（给设不了请求头的客户端）。E2E 第一版没带，网关
   回 `IDEMPOTENCY_KEY_REQUIRED` —— 适配器不另判一套"写动作要不要键"，统一交给网关按
   权限级别说话。**Agent 落子与真人落子遵守同一条幂等纪律**，这就是"Gateway 的一个 Adapter"
   的含义。
6. **验收脚本的诚实性：单账号下的 join 断言写什么。** 验收机注册关闭、只有一个登录账号 ——
   "Human B 从分享链接进来"这一跳没有第二个真人。脚本没有假装它发生了：默认断言是
   "场主幂等兑票不加行"（参与者=2：场主+Agent），而 `CHECK_USER_B`/`CHECK_PASS_B` 给出第二
   个账号时同一脚本自动升级为完整三人局断言（参与者=3）。**断言要跟事实走，而不是跟
   叙事走** —— 硬写 3 会把幂等兑票的真行为（这正是要验的）判成失败。
7. **生态链上每一跳都有守着自己的另一条断言，E2E 不重复它们，只把链走通。** §107 的"DH
   零改动"由 `DhApplicationKnowledgeArchitectureTest` 守（源码里不出现 `gomoku`）；
   §109–§111 的 LLM 三级收窄由 `check-lap.sh` 断言 11 与 `AgentApplicationFlowTest` 守；
   Agent 的"无 Application 专用 API"（原则 5）由 §118 规则守。`check-ecosystem.sh` 验的是
   **这些已被单独证明的环节拼成一条链之后仍然成立** —— E2 的开局、E4 的定向邀请、E5 的
   同一行 resource、E7 的同一条审计链，单独看哪一环都不新，连起来走通才是 §126。

**R5 的关键决定**（两个新增参考应用 + DH 提醒只读改造）：

1. **提醒的真相搬进了应用，DH 的 REST 面一个字没改。** `reminder_item` 表归
   `com.luxera.reminder`；DH 的 `ReminderService` 只剩两件事 —— 读 `reminder://owner/{userId}`
   （经 `ResourceProjector` 投影，不是读自己的表）、写 `reminder.create` / `reminder.complete` /
   `reminder.cancel`。`ReminderRepository` 连同它的 JPA 一起删了，`Reminder` 从实体降级成 DTO。
   `scripts/check.sh` 新增一节（16 条断言）钉住"契约未变"，其中一条专门断言旧的 `reminders` 表
   **在整条链路跑完之后一行都没多** —— 两个 Source of Truth 并存是全轮最大的回退风险。
2. **DH 调提醒应用时身份显式写成 `HUMAN(userId)`，不是 `AGENT(companionId)`。** 提醒是"主体型资源"，
   URI 里的 `ownerId` 就是这个人，应用会核对 `principalId == ownerId`。写成数字人自己会被
   `NOT_RESOURCE_OWNER` 拒掉 —— 一个从报错里很难看出来的错。
3. **每次调用带一个全新的 `correlationId`。** 进程内调用的幂等键从 `correlationId + target` 派生，
   而提醒的 target 永远是同一个收件箱；共用一个 id 会让"提醒我喝水"说两遍只得到一条。
   "每年最多一条生日提醒"这种真正的去重规则，由**应用**按数据判定，不由调用方自己记着。
4. **`ApplicationNotificationBridge`：DH 侧第二个 `APPLICATION_EVENT` 消费者。** 它和
   `AgentApplicationFlow` 看同一条事件的两个侧面（"该不该说给他听" / "我该做点什么"）。
   应用在自己的事件载荷里放一个 `notify` 块，桥就落一条通知；`type` 是不透明字符串，原样透传。
   这个类里没有一个字提到提醒 —— 生日提醒因此不再需要 DH 侧的扫描器。
5. **五子棋证明的是"同域第二个应用不需要改 DH"。** 它与井字棋的 action id **完全一样**
   （`game.create` / `game.state` / `game.make_move` / `game.surrender`），只有 URI scheme
   （`gomoku://match/{id}` vs `game://session/{id}`）不同 —— 发现链的第 2 级（一个能力多个候选）
   这才算真的被走过。

**R6 的关键决定**（MCP 只是一个适配器）：

1. **`MCP Session ≠ ApplicationSession`。** MCP 的协议状态（协商版本、客户端信息）只活在适配器内存里
   （`McpSessions`），`application_session` 一行都不会因它增减 —— 归属链是平台的概念，不是传输的概念。
   这条不变量两边都有测试钉住：`McpProtocolTest` 断言整条往返前后 `application_session` 计数不变，
   `check-lap.sh` 断言 14 在真实服务上再断言一遍。**两处都同时断言棋盘真的变了** —— 否则"没创建会话"
   也可能只是因为那条链路根本没执行。
2. **`/mcp` 在 `SecurityConfig` 里是 `permitAll`，这不是漏洞。** MCP 客户端是外部 Agent，手里没有 JWT，
   只有 `X-Mcp-Principal` + 服务密钥 —— JWT 那一层**表达不了** MCP 的身份。留在
   `anyRequest().authenticated()` 后面的结果是每个 MCP 请求都在过滤器上变成 Spring 默认的 403
   （`{"status":403,"path":"/mcp"}`），连 `initialize` 都到不了控制器。真正的门在控制器第一步：
   服务密钥留空即 MCP 完全关闭。
   这个坑是 `check-lap.sh` 抓到的，**进程内测试抓不到** —— `application-platform` 的测试应用没有
   `SecurityConfig`（它在 `platform-kernel`），过滤器链压根不在场。补的守卫是 `bootstrap-app` 的
   `McpEndpointSecurityTest`（唯一同时看得见两者的地方）。
3. **工具的 `target` 是平台级的，动作自己的输入是平铺的。** 工具 schema = 动作 `inputSchema` + 一个必需的
   `target`，刻意**不**套一层 `{"input":{...}}` —— MCP 客户端照着 schema 填，声明成嵌套就得多填一层，
   而那一层除了复述 LAP 的内部结构之外没有用处。`expectedResourceVersion` 与 `_idempotencyKey`
   能收但不写进 schema：后者是给设不了请求头的客户端的退路，写进 schema 只会邀请模型为一个它无从知道的
   字段编个值。
4. **工具名撞车时整个目录一起退化，不是只改撞的那个。** 井字棋与五子棋的动作 id 完全一样，靠应用短名
   分开；短名再撞（`a.b` 与 `c.b`）就都改用全名 —— 只给其中一个改的话，工具名会变成"取决于另一个应用
   存不存在"的东西。
5. **AGENT 身份目前没有 HTTP 安装入口**（`/install` 从 JWT 解析，那是真人的路；MCP 面明确拒绝 `HUMAN`
   声明）。所以断言 14 里的 AGENT 安装行是 SQL 造的，`McpProtocolTest` 直接调 `InstallationService` ——
   这是**已知的产品缺口**，不是测试的将就。

**R7 的关键决定**（Agent 的 LLM 契约：能力 → 应用 → 动作）：

1. **能力选择只在主动路径上，反应路径不做。** 事件里已经点名了 resource（"某个应用里轮到你做一件事"），
   再问一遍"这该用哪个应用"是多余的 —— 每次多花一次 LLM 调用，答案还永远是"就是它"。
   能力选择是**用户说了一句话**时的前门，所以 `react()` 里没有它。
2. **`pickAction` 的三分法**：LLM 点了候选里的名 → 用它；没点名而候选只有一个 → 用它（此时"选哪个"
   本就没有信息量，LLM 的活儿是填 `input`）；点了不在候选里的名、或候选不止一个却没说选哪个 →
   **不行动**。第三条最要紧：替它补一个就是启发式，而"绝不降级到启发式"是用户明确要求的性质。
   井字棋轮到数字人时给的是**两个**候选（落子 / 认输），所以契约要求模型真的说清楚要哪个 ——
   `LapEndToEndTest` 的 stub 也照契约回了 `actionId`（**这条契约变化就是它抓出来的**）。
3. **只有一个候选时不问第二次 LLM。** 一个候选的"选择"只是在花钱听模型复述一遍输入。
4. **能力目录的指纹进 metadata**（按 id 排序后 SHA-256，`stableHash`）。事后翻 `llm_calls` 能知道模型
   当时看的是哪一版目录；"目录没变而指纹变了"会让这个字段失去全部意义，所以先排序再拼。
5. **守门的是平台，不是模型。** 四个幻觉出口全部被拒：能力不在目录里、应用不在候选里、动作不在候选里、
   含糊不点名。每一级都是"模型的回答是输入，校验说了算"。
6. **`DhApplicationKnowledgeArchitectureTest` 刻意不用 ArchUnit。** 要禁的是字符串与变量名
   （`game.make_move`、`board`、`井字棋`），ArchUnit 看的是依赖与类名，两者都看不见 ——
   那样写出来的是一条**通过但什么都没检查**的规则，比不写更糟。所以它是源码文本扫描；
   提醒应用的身份与词汇只允许出现在 `tool/ReminderService.java`（DH 侧通往应用的那**唯一一道门**），
   并额外断言白名单**不是空壳**（若提醒被整个删掉，规则会因为"没人再提它"而假绿）。
7. **断言 11 从"跳过"改成双模式，是这一轮最诚实的一次让步。** 本环境跑的是 mock LLM，
   而 mock 下"数字人不行动"是设计行为 —— 于是"棋盘上出现 O"在这台机器上永远不可能通过。
   与其 skip 掉，不如断言**保险丝已就位**：事件确实走完了 应用 → 平台 → 数字人，流程读到了资源、
   看到了待办动作、然后**按设计拒绝**（判据是服务日志里的那两行）
   ；"真的走了这一步"要显式 `LAP_EXPECT_AGENT_MOVE=1` 才要求，日志文件缺失则**判失败**而不是跳过 ——
   和断言 14 缺 `LAP_MCP_SERVICE_KEY` 同一个政策。

**R2/R3 的关键设计**：`TicTacToeGameService` 在提交后不再直接投递事件，而是发一条
`contracts.application.ApplicationEvent`（经注入的 `ApplicationEventSink`）—— 这是整个搬迁中
唯一一处"越界编辑"。DH 的 `DhApplicationEventSink` 把它翻译回 `ExternalEvent`，
事件 id 仍是确定性的（`game://session/{id}#MOVE-0`），所以 DH 侧的去重照样生效。

**两级 `agentTrigger` 闸门**：manifest 里 `events[].triggersAgent`（类型级）**且**
事件 `data.agentTrigger`（实例级）都为真才唤起 Agent。故意做成两级而不是让 sink 硬编码 `true` ——
否则 `AgentApplicationFlow` 里的过滤就变成了恒真式，测试也守不住什么。

**R4 的四个关键决定**：

1. **幂等是两段式事务，不是 `check → insert → execute`**（后者是 TOCTOU）。先在一个独立事务里插入
   `IN_PROGRESS`（唯一索引 `(principal_type, principal_id, idempotency_key)` 即锁，插入失败即已存在）
   并提交，再执行 handler + 写 Resource + 回填终态（同一事务）。终态三选一：
   `IN_PROGRESS → {SUCCESS, FAILED, EXPIRED}`。`ActionInvocationReaperJob` 按 `started_at` 超时回收
   崩溃遗留（未超时 `< 60s` → `409 IDEMPOTENCY_IN_PROGRESS` 而不是阻塞等待；已超时 → CAS 抢占用重放）。
   **不变量**：留在 `IN_PROGRESS` 的行，意味着第二个事务从未提交 —— 也就是那个动作**确定没发生**。
2. **READ 从不记 invocation，也从不要求幂等键。** 这条是被旧代码里的 `"state-"+roomId` 逼出来的：
   一旦幂等对读生效，同一房间的第二次 `game.state` 会重放第一次的旧棋盘，表现为"数字人在同一步重复落子"。
   `check-lap.sh` 断言 8 就是这条的保险丝。
3. **Resource 的每次写入都走 CAS**（`WHERE uri = ? AND state_version = ?`），影响 0 行即
   `409 STATE_CONFLICT` 并把当前版本与状态一起带回，让调用方立即重读重试；两个 principal 抢同一步
   得到的是干净的冲突而不是丢招。
4. **Handler 注册表的键是 `(applicationId, version, actionId)`。** 井字棋与五子棋都有 `game.make_move`，
   用 `Map<String, ActionHandler>` 会让后注册的静默覆盖先注册的；`ManifestRegistrarTest`
   专门断言两者并存互不覆盖。

另外两处**行为修正是有意的**：`SESSION_ENDED` 从 400 改成 **409**（会话结束不是"你的请求写错了"，
该做的是换一个 target 而不是改载荷）；`INSTALLATION_INACTIVE` 与 `NOT_INSTALLED` 是**两个不同的拒绝** ——
前者该去重装，后者该去装。HTTP 状态与 `ActionStatus` 的映射集中在**唯一一个** `ActionStatusMapper`，
REST / MCP / `ApplicationRuntimePort` 共用。

**R8 的关键决定**（生命周期状态机 + REMOTE + 投递 + 收尾）：

1. **状态字段必须真的被读，否则它与不存在没有区别。** 这是整轮里唯一一条能解释"为什么非做不可"的
   理由，所以有两条硬后果：`transition()` 把 `application.status` 与 `application_version.status`
   一起推进（后者正是 `VERSION_IMMUTABLE` 的依据），发现面按 `isDiscoverable()` 过滤 ——
   被挂起的应用从能力/应用/动作三个列表上**一起消失**。`check-lap.sh` 断言 16 在真实服务上验这一条：
   挂起 `com.luxera.gomoku` 后它从 `GET /api/v1/capabilities/game.play/applications` 里没了，
   而 `com.luxera.tictactoe` 还在。
2. **`SUSPENDED` 是软停用，不是停机。** 从发现链上撤下，但**已安装的调用不受影响** ——
   一盘正在下的棋不该因为运营点了"暂停"而突然走不动。`DEPRECATED` 才是终态（`canMoveTo` 恒 false）。
3. **应用与版本行的状态必须一起动。** 否则会出现"应用已停用、版本仍在架上"这种谁也不知道该信哪一份
   的状态。**这条在实现里抓出过一个真 bug**：恢复分支的条件写成了"当前是 PUBLISHED"，而挂起那一步
   刚把版本改成 SUSPENDED，于是那条分支是一段**谁也没走到过的死代码**，症状是"应用恢复了、版本还在
   架下"——恢复了个寂寞。`LifecycleStateMachineTest.theVersionRowsMoveWithTheApplication` 抓的它。
   正确条件是"当前是 SUSPENDED"，且**只放回 `latestVersion` 那一版**（单独被废弃过的历史版本不该被
   这次恢复复活）。
4. **REMOTE 的每一条保证都落在"远端是别人写的"这个前提上。** 所以：转发的是**派生**幂等键而不是调用方
   那把（调用方的键只在 `(principal, key)` 里唯一，两个人各用 `"1"` 会在远端撞成同一次调用；派生键是
   这次逻辑调用的确定函数 —— 重试仍幂等、跨调用方必不同）；签名把**时间戳纳入 HMAC**
   （`sha256=HMAC(secret, timestamp + "." + body)`），否则一次被截获的请求可以被无限期重放；
   `authRef` 是**名字**不是密钥，manifest 里永不出现密钥；超时/连不上/HTTP 错误码一律映射到与 REST
   **同一套** `ActionStatus`。一个连不上的远端是"暂时不可用"（`REMOTE_UNAVAILABLE`）而不是"你没权限"——
   调用方该做的是稍后重试。`authRef` 解析不到则相反：那是**平台没部署好**（`REMOTE_AUTH_UNRESOLVED`，
   FAILED 而非 DENIED —— 报 403 会让人去查权限，查半天发现是配置漏了）。
5. **`INBOX` 订阅的真相是一张表，不是一个内存队列。** 主键是 `sha256(eventId + "@" + subscriptionId)`
   ——**该事件的确定函数**，于是"同一事件重复入队"是同一次投递而不是第二次，至少一次投递的代价
   （重复）由这个主键在库层面兜住。`OutboxRelay` **刻意不带 `@Transactional`**：每行自己的 `save`
   就是一次事务，投递成功与状态回写不会因为隔壁行失败而一起回滚。失败**绝不静默丢弃** ——
   `attempts` + `last_error` 落库，超过上限转 `DEAD` 停手，留一行能查的死信好过让它消失。
6. **回收会话只结束、从不删除。** 被结束的会话仍然解释得通（还记得是谁、装的哪一版、在哪个安装下开的），
   而删掉的会话会让它名下所有 `action_invocation` 变成查不到上下文的孤儿。阈值 7 天与
   `ActionInvocationReaperJob` 的 60 秒差三个数量级，因为问的是两个不同的问题："这个人还在玩吗"
   与"这次调用还活着吗"。
7. **`lap-drop-legacy.sh` 是运维脚本，不是启动钩子。** 应用启动时删表是 footgun（一次误启动就没了）。
   四张遗留表（`dh_application` / `dh_game_session` / `dh_application_action_log` / `reminders`）在
   R8 手工执行删除，`check-lap.sh` 断言 1 之前一直是"跳过"的那半边也同时打开 —— 逐张断言它们**不存在**，
   于是"哪天有人把写入方加回来"会立刻炸在 CI 上，而不是等到某天发现数据长了两份。
8. **六个模块第一次同处一个 Spring 上下文时，抓出了一个所有既有守卫都看不见的 bug。** LAP 的
   `lap_outbox` 仓储原本叫 `OutboxEventRepository`，与平台核心里那张 `outbox_event` 表的仓储**撞了简名**
   —— Spring 的默认 Bean 名是*类简名首字母小写*，不是全限定名，于是 `bootstrap-app` 直接起不来：
   `The bean 'outboxEventRepository' … has already been defined`。它隐形的原因是**每一层守卫都恰好
   管不着**：各模块自己的测试只看得到一个类；包归属与 Maven 依赖全都正确，`check-v10.sh` 十条边界规则
   一条都不会响；ArchUnit 的依赖规则也看不到（类型不同、没有依赖关系，冲突的东西是**一个字符串**）。
   修法是把 LAP 那个改名为 `LapOutboxRepository`（对应它真正映射的 `lap_outbox` 表），
   **不是**打开 `allow-bean-definition-overriding` —— 那只是把失败推迟到运行期。
   补的守卫是 `bootstrap-app` 的 `BeanNameCollisionArchitectureTest`：扫描全部六个模块，
   按 Spring 的规则算出每个 Bean 的名字并断言不重复；扫描面**刻意比"带注解的类"宽一层**，
   因为 Spring Data 仓储接口没有注解（靠继承 `Repository` 标记被扫到）—— 只扫 `@Component` 一族
   会正好漏掉这次的真凶。

### 参考应用口径（统一说法，避免后续误判工作量）

**一个迁移应用（TicTacToe）+ 两个新增参考应用（Gomoku 15×15、Reminder 提醒/日程）。**
三者的存在意义是**证明平台**而不是攒应用数量 —— R7 的终局验收就是"加 Gomoku 只涉及
Gomoku 的 Manifest 与 Handler，`AgentRuntime` / `AgentApplicationFlow` / Perception / Cognition /
Decision 零修改"。**这条已经成立**：五子棋落地时 `digital-human-platform` 的改动为零（那一轮
DH 的改动全是提醒只读改造带来的），R7 之后它又多了一道机器守卫 ——
`DhApplicationKnowledgeArchitectureTest` 断言 DH 的源码里根本不出现 `gomoku` / `tictactoe` /
`game.make_move` / `board` 这些词，所以"加第二个游戏要改 DH"从"我们没改"变成了"改了会红"。
提醒的所有权是**单一数据源：应用拥有，DH 只读**。

### 当前验收

- `mvn test`：**808 测试全绿** —— contracts 23 / platform-kernel 0 / chat-platform **34** /
  digital-human-platform **309** / application-platform **399** / bootstrap-app **43**
- `bash scripts/check-v10.sh` → `check-v10 OK`（41 个顶层包分属 5 个所有权模块，10 对引用 + 10 对 pom）
- `bash scripts/check.sh`（起 jar）→ **✅ 全量验收全部通过**（聊天/数字人链路无回归；
  含 R5 新增的 16 条提醒契约断言）
- `bash scripts/check-lap.sh` → **✅ 验收通过**；断言 2 / 3 / 9b / 10 / 11 / 14 / **16 / 17** 已转正
  （`reminder.manage` 入目录、`game.play` 两个候选、未安装 → `NOT_INSTALLED`、
  装上提醒应用后经同一个 execute 端点建提醒并读回收件箱；**断言 14**：MCP 客户端与真人在
  **同一行** resource 上对弈 —— `board[0]=X`(真人 REST) / `board[4]=O`(Agent MCP)，且
  `application_session` 一行没多；**断言 11** 见 R7 的决定 7；**断言 16**：挂起 `com.luxera.gomoku`
  后它从 `GET /api/v1/capabilities/game.play/applications` 里**真的消失**、跳步 → `409 ILLEGAL_TRANSITION`、
  真人 → `403`、复原后重新出现，最后对已发布版本写 manifest → `409 VERSION_IMMUTABLE`；
  **断言 17**：`INBOX` 订阅的事件先落进 `lap_outbox` 一行，再由 relay 在 20 秒内投成 `DELIVERED`，
  订阅自己的 `last_delivered_at` 同时跟上 —— 只断言"有一行"会让一个从不投递的 relay 全绿，
  只断言"投出去了"会让一个不落库就直投的实现全绿）；**断言 18**：开会话返回 §16 形状、应用详情给出
  十态 `status` + §4.1 三列布尔 + 五条 Surface；**断言 19**（R12）：走**聊天平台**的三个会话上下文端点
  在真实进程里开一个应用 —— 库里 `application_session.conversation_id` 一致、落下的是
  `message_kind=APPLICATION_CARD` 的 `system` 消息、分享铸出的**明文令牌不进库**、
  开一个不存在的应用要回 `404 UNKNOWN_APPLICATION`（拿到 500 就说明跨模块的错误翻译断了）；
  断言 13 确认 `GET /api/v10/applications` → **404**
- **断言 1 的"旧表不存在"半边已在 R8 打开**（`lap-drop-legacy.sh` 已执行）：
  `dh_application` / `dh_game_session` / `dh_application_action_log` / `reminders` 四张表逐张断言不存在，
  于是"哪天有人把写入方加回来"会立刻炸在 CI 上
- 仍只在**真实 LLM** 下执行的 2 项：断言 12（reality ledger 条目）与断言 15（共享世界）——
  两者是**同一个前提**：数字人真的动手了，而在本机的 mock LLM 下"不行动"是有意为之，所以它们只在
  `LAP_EXPECT_AGENT_MOVE=1` 且服务接了真实 LLM 时才会执行（断言 12 的判据是 `timeline_event` 里
  那一条 `APPLICATION_ACTION_EXECUTED`）
- `cd frontend && npm test` → **24 测试全绿**（`SurfaceHost.test.tsx` 15 条五种 Surface 的行为差别 +
  `ApplicationCardBubble.test.tsx` 9 条卡片消息的降级路径）；`npm run build` → 通过
- `bash scripts/check-remote-app.sh`（R14）→ **✅ 六条全绿**：自己起 Python 五子棋与带
  `LAP_REMOTE_APPLICATIONS` 的 jar，验远端应用与内置同能力并存（R1）、开发者从零到应用行 +
  重认领 409（R2）、一步棋跨 Java→HTTP→HMAC→Python 四层且**远端状态投影成 resource 行读得回**
  （R3）、同键重发手数不变（R4）、远端 409 以 `REMOTE_CONFLICT` 转述且原话带回（R5）、
  manifest 里只有 `authRef` 没有密钥（R6）；两个真缺陷由它抓出（过滤器层 403、
  `UNKNOWN_DEVELOPER` 落 default 400）
- `bash scripts/check-ecosystem.sh`（R15）→ **✅ E1–E7 全绿**：自己起远端五子棋与带
  `LAP_REMOTE_APPLICATIONS` + `LAP_MCP_SERVICE_KEY` 的 jar，把 §126 整条链一次走完 ——
  E1 发现链上内置与远端两个五子棋并存；E2 Human A 开局落子天元；E3 铸票兑票（幂等不烧名额）；
  E4 定向邀请 Agent 进场 + 经 MCP `remote-gomoku.game_make_move` 落子；E5 同一行 resource 上
  `board[112]=X`（Human）/ `board[7]=O`（Agent）、手数=2、参与者行数与兑票幂等一致；
  E6 能力→应用→动作三级接口面 + `reminder.manage` 候选里没有游戏（能力隔离）；E7 审计账本上
  HUMAN 与 AGENT 各有行动 —— 同一条审计链。一个真缺陷由它抓出（`ParticipantService.join`
  对已在场者也拿邀请 role 覆盖原 role，场主兑自己的分享链接后被降成 MEMBER）
- **CI 顺序**（每一轮都照这个跑）：`check-v10.sh` → `mvn test` → 起 jar（断言 14 要求带
  `LAP_MCP_SERVICE_KEY`）→ `check.sh` → `check-lap.sh` → `check-remote-app.sh` →
  `check-ecosystem.sh` → `npm test` → `npm run build`
- **LAP v1 的九轮（R0–R8）与 v2 的七轮（R9–R15）已全部完成。**

---

## V10 · 三系统边界与因果链落地（2026-08）

> **方案依据**：《Companion Agent V10 Detailed Architecture》—— V10 不是"收到消息就调 LLM 回复"的聊天机器人，
> 而是 **Persistent Digital Person System**：Chat Platform（外部世界）/ Client Simulator Platform（数字人的设备与身体）/
> Digital Human Platform（数字人的生活、意识、认知与行动）三个独立系统，固定因果链
> **World → Event → Perception → Awareness → Cognition → Decision → Action → Reality**。

### V10 第六轮 · PersonActor 全面接管 + 复查决策策略化（2026-08）

1. **Person Actor 全面接管（V10 §20）**：消息提交从全局线程池迁移到 per-person mailbox
   （**严格 FIFO：提交顺序=执行顺序**）；空闲 30s 自动回收（不累积线程，回收后自动重建）；
   `process` 同步路径锁统一到 registry（同步/异步互斥，状态修改永不走并发）；任务异常隔离。
2. **已读复查决策策略化（V10 §13）**：`PendingMessageReevaluationJob` 策略预筛 ——
   忙/疲惫时 DecisionPolicyEngine 判定 DelayReply（延后复查，不打扰认知、省一次 LLM）；
   其余走原有 Brain 决策。真人忙的时候"想起也不会立刻回"。
3. **迭代总结**：6 轮迭代完成 —— MVP 14 条验收全部落地、V10 §24 模式对照表全部落位、
   全量 262 测试全绿（新增 71 个）、修复 7 项既有问题。详见 docs/V10-iteration-round6.md。

### V10 第五轮 · Conversation Runtime 唯一文本入口 + Prompt 分层缓存（2026-08）

1. **Conversation Runtime（V10 §15，MVP 验收 14）**：`digitalhuman.conversation` ——
   `ConversationRequest`（Builder，分层 Prompt：Stable Prefix/Semi-Stable/Dynamic Suffix）→
   `ConversationRuntime.generateDrafts` 唯一文本生产管道：LLM → 输出契约解析 →
   质量闸门 → 失败重生成（≤2 次）→ 仍失败**不生成**（像真人没说出口）。
   `ProactiveEngine` 主动消息已收口到本管道（过旁白/AI 腔闸门）。
2. **Prompt 分层缓存（V10 §19）**：`PromptLayerCache` —— 稳定层 SHA-256 哈希缓存
   （同 hash 不重复渲染，动态内容绝不进缓存，人格变化 hash 自然失效）+ 命中率统计，
   诊断端点 `GET /api/v10/conversation/cache-stats`。
3. **PLAN_REMINDER 接入**：计划到点 → 时间触发激活（PLANNED → ACTIVE，V10 §7.3 状态机）；
   `LifeRuntime` 创建计划即排程提醒。
4. **MVP 14 条验收全部落地**（见 docs/V10-iteration-round5.md 对照表）。

### V10 第四轮 · Life Scheduler + Relationship Projection（2026-08）

1. **Life Scheduler（事件驱动 + 时间触发，V10 §7.4）**：`digitalhuman.life` ——
   `LifeEventScheduler` 接口 + `life_schedule` 表（同 scheduleId 幂等）；
   `LifeScheduleJob`（每 20 秒，配置化）到点分发；`LifeEventDispatcher` 幂等收尾活动
   （ACTIVE→DONE + Reality Ledger ACTIVITY_ENDED）。**生活准点变化，不靠轮询猜**；
   活动创建即排程结束事件（tick 退化为兜底，双路径幂等）。
2. **Relationship Projection（V10 §17，Projection Pattern）**：`digitalhuman.relationship` ——
   `RelationshipProjector`（纯函数：账本 → 互动摘要）；`reconcile` 以账本修正关系事实字段
   （messageCount/lastInteractionAt）；每日核对 Job（配置化）+ 诊断端点
   `GET /api/v10/relationship/projection`。**关系事实层只信 Reality Ledger（Memory 不能覆盖 Reality）**。
3. **新增表**：`life_schedule`。

### V10 第三轮 · Outbox 可靠发布 + stateVersion 乐观锁（2026-08）

1. **Outbox（双写一致性，V10 §21.3）**：`digitalhuman.outbox` —— `outbox_event` 表
   （event_key 唯一幂等入队 / PENDING→PUBLISHED / 失败退避重试超限 FAILED）；
   `OutboxPublisher` 业务事务内入队；`OutboxRelayJob`（每 5 秒，配置化）投递到事件链。
   用户消息落库同事务入队兜底 —— 进程崩溃后由 Relay 补发，确定性 eventId + processed_event
   幂等保证**补发/重放绝不重复处理**；未来换 MQ 只改 Relay 投递目标。
2. **StateVersionGate（LLM 旧结果丢弃，V10 §21.1，MVP 验收 12）**：`digitalhuman.state` ——
   LLM 调用前快照版本，返回后乐观提交；版本已变 → 结果作废（不覆盖新状态）。
   已接入回复生成路径（防御层）。
3. **新增表**：`outbox_event`。

### V10 第二轮 · 感知策略化 + 决策策略化（2026-08）

1. **Perception Runtime（Strategy Pattern）**：`digitalhuman.perception` —— 4 级感知
   NONE/SUBCONSCIOUS/AWARE/FOCUSED（阈值 0.2/0.5/0.8）；`MessageNotificationStrategy`
   （声音/震动/静音/勿扰 + 距离/噪声/活动注意力修正，全程规则无 LLM）、`LifeEventStrategy`、
   `TimeEventStrategy`；`SnapshotFactory`（Adapter）从 AgentState/PhoneState/Schedule 提取快照。
   **同一消息在不同环境下可能被感知或完全不知道**。
2. **Decision Runtime（Policy Pattern）**：`digitalhuman.decision` —— sealed `PersonDecision`
   （Ignore/InspectDevice/Reply/DelayReply/ChangeActivity）；`DecisionPolicyEngine` 按 @Order
   确定性执行 Ignore→ReplyLater→ReplyNow→ChangeActivity→InspectDevice。
   **Agent 可以决定忽略、查看、立即回复或稍后回复**。
3. **完整链路编排**：`PerceptionDecisionOrchestrator` —— ExternalEvent → Perception → Decision；
   诊断端点 `GET /api/v10/perception/explain`（验收可视化）。
4. **顺带修复**：高频定时 Job（排程动作/已读复查/记忆衰减）硬编码 cron 抢占测试连接池 → 配置化。

### V10 第一轮 · 三系统边界（2026-08）

### V10 核心升级（第一轮）

1. **Simulator Platform（Command Pattern + Facade）**：`simulator` 包 —— `SimulatorCapability` 能力接口
   （`SendMessageCapability` / `ReadMessagesCapability` / `ListConversationsCapability` /
   `UpdateDeliveryStatusCapability`），每个能力对应一个最小 scope（chat.read / chat.send / conversation.list / delivery.update）。
   `SimulatorSession`（DISCONNECTED→CONNECTING→CONNECTED + scopes 授权，禁止万能权限）+
   `SimulatorClient`（Facade）：**数字人访问外部聊天世界的唯一入口** —— 未来替换聊天平台只需实现新 Capability。
2. **外部事件链（Chain of Responsibility）**：`digitalhuman.event` 包 —— `ExternalEvent`（eventId 幂等 +
   correlationId 因果追踪）→ `EventProcessingChain`（Validation → Deduplication → Route）。
   `processed_event` 表幂等短路：同 eventId 重放不重复触发认知（MVP 验收 13）。
   **消息内容不再直接注入 Agent** —— 事件 payload 只带 messageIds 引用，数字人通过 Simulator 的
   ReadMessagesCapability 自行"查看"（MVP 验收 3/4）。
3. **Reality Ledger（Event Sourcing 心智）**：`digitalhuman.reality` 包 —— `timeline_event` 表
   append-only（实体 `@Immutable` 禁止 UPDATE），`RealityLedger` 唯一写入口（同 eventId 幂等），
   支持按时间正序回放（MVP 验收 9）。已接：MESSAGE_SENT / MESSAGE_READ / MESSAGE_DEFERRED / MESSAGE_IGNORED。
4. **Person Actor（Actor 模型）**：`digitalhuman.actor` 包 —— 每 Person 一个 mailbox（队列 + 单消费者线程），
   同 Person 任务严格串行、不同 Person 并行（V10 §20）。`PersonActorRegistry` 提供统一 tell 入口。
5. **输出质量闸门（ConversationOutputValidator）**：`digitalhuman.conversation` 包 ——
   `NarrationRuleValidator` 拦截旁白/舞台动作/AI 腔（（笑了笑）/ *smiles* / 她想了想 / 作为AI…），
   `OutputValidationChain` 组合验证；回复发送前必须通过闸门，未通过则"没说出口"（MVP 验收 14/6）。
6. **AgentRuntime 边界收口**：回复发送/批量已读/延迟/忽略全部改走 SimulatorClient + 写入 Reality Ledger；
   用户消息入口改为 `CHAT_MESSAGE_DELIVERED` 外部事件（确定性 eventId）经事件链进入认知。

### V10 新增数据表

| 表 | 用途 |
|----|------|
| `timeline_event` | Reality Ledger（append-only 真实经历，@Immutable 禁改） |
| `processed_event` | 外部事件幂等记录（重试/重放短路） |

### V10 本轮验收

- `mvn test -Dtest='PersonActorTest,NarrationRuleValidatorTest,SimulatorClientTest,EventProcessingChainTest,RealityLedgerTest'`：**29 个新测试全绿**
  （Actor 串行性 / 旁白拦截 / Capability scope 授权与收发闭环 / 事件链校验-去重-路由 / Ledger 幂等与回放）
- 全量回归：既有 V9 测试不受影响
- 设计模式落位：Command（Simulator）、Strategy→Chain of Responsibility（事件链）、Facade/Adapter（SimulatorClient）、
  Actor（PersonActor）、Event Sourcing（RealityLedger）、Validator Chain（输出闸门）、Registry（EventRouter/PersonActorRegistry）

### V10 后续轮次（路线图）

- **第二轮**：感知策略化（PerceptionStrategy）与决策策略化（DecisionPolicy，Ignore/ReplyNow/ReplyLater/InspectDevice）
- **第三轮**：Outbox 事件发布（双写一致性）、stateVersion 乐观锁校验（LLM 旧结果丢弃）
- **第四轮**：Life Scheduler（活动/计划时间触发，替代轮询）、关系投影（Relationship Projection from Reality Ledger）
- **第五轮**：Conversation Runtime 统一文本生成入口（其余模块禁止产文本）、Prompt 分层缓存

---

## V9 · 连续心智与事实一致（2026-08）

> **V8 解决"Agent 能不能回答"；V9 解决"这个 Agent 在这一刻为什么会这样回答，而且下一刻它仍然是同一个人？"**
>
> 核心：模型不是 Agent 的全部，模型只是 Agent 的认知器官。事实由 Reality 层保证，连续心智由 Cognitive Session 保存，表达与决策解耦，稳定上下文可缓存。

### V9 核心升级

1. **Cognitive Session（连续心智）**：`cognitive_sessions` 保存 current_focus / current_thought / current_intention / active_plans / emotion_summary，带 `state_version` 乐观锁 —— 用户消息与主动事件并发写入不互相覆盖。她知道自己上一刻在想什么、正在关注什么，而不是每轮重新猜。
2. **Reality Ledger + Plan 状态机**：`plans`（概率性计划：confidence/flexibility/expected_time）+ `plan_revisions`（变更因果链）——计划可以没有、可以改变，改变必须有原因；突发事件按权重打断低优先级计划（SUPERSEDED）；用户追问"你不是说要去跑步吗"时沿 revision 链自然解释（"本来是打算去的，后来朋友喊我吃饭就没去成"），不硬圆。
3. **Cache-aware Context Compiler**：分层编译 L0 稳定前缀（身份/人格/行为准则，版本化 hash 稳定 → provider prefix cache 命中）/ L1 会话前缀（关系/用户模型）/ L2 动态（活动/情绪/想法/计划/记忆）/ L3 当前轮（行为意图/预算/表达提示）。稳定内容永远靠前，动态内容靠后。
4. **LLM Call 可观测性**：`llm_calls` 记录每次调用的 model/token/latency/路径/上下文 hash；prefix cache 命中估计（同 agent 同 stableHash 连续调用）。`GET /api/companions/{id}/v9/metrics` 查看。
5. **Brain → ResponseIntent → Expression 解耦**：Brain 输出回应意图（期望长度/拆几条/节奏/语气），Expression 只决定怎么说 —— 表达不得改变 Brain 已确认的事实。
6. **Fast / Deep 路径调度**：普通寒暄走 FAST（跳过记忆/用户模型重检索，轻量上下文）；重要消息（DELIBERATION+）走 DEEP 完整上下文。降低延迟与成本。
7. **per-agent 单写者锁**：同 agent 的消息/主动事件/后台任务串行写入，防止并发覆盖状态。

7. **Session Rolling Summary**：`session_summaries` —— 会话达 40 条后早期消息压缩为分节摘要（facts/unresolved/relationship/plans），每 20 条增量重写；近期原文保留，远期摘要化；异步生成（轻模型）不阻塞主链路。
8. **醒来补处理**：她睡着时收到的消息，醒来后自动补处理（`WakeupCatchUpService`）——真人睡醒拿起手机看到全部夜间消息，已读/回复闭环。
9. **Reality 一致性校验**：`RealityConsistencyChecker` —— 表达与当前现实冲突（"忙工作时说在休息"/"已被打断的计划还要去"）禁止直接发送，重新生成一次，仍冲突则不说出口。
10. **Skill 按需加载**：按感知 Intent 选技能（难过→陪伴/安抚，规划→事件模拟），注入当前轮（L3），不破坏 L0 稳定前缀缓存。

### V9 新增数据表

| 表 | 用途 |
|----|------|
| `cognitive_sessions` | 连续心智（当前关注/想法/计划摘要 + state_version 乐观锁） |
| `plans` | 概率性计划（confidence/flexibility/expected_time/触发条件） |
| `plan_revisions` | 计划变更因果链（创建/激活/完成/取消/打断，带原因） |
| `llm_calls` | LLM 调用观测（model/token/latency/路径/上下文 hash/缓存估计） |
| `session_summaries` | 会话滚动摘要（早期事实分节沉淀，远期摘要化） |

### V9 验收

- `mvn clean test`：**全部测试全绿**（含 CognitiveSession / PlanService / ContextLayer 新增测试）
- `scripts/check.sh`：表结构 + 认知会话 + LLM 观测端点
- 端到端：连续聊天心智不漂移（认知焦点持续）；计划被突发事件打断后追问可自然解释；短消息走 FAST、重要消息走 DEEP

---

## 当前版本 · Digital Person

> **核心目标：让用户无法仅通过聊天行为判断对方是 AI。**
>
> 架构原则：**Chat Platform 是通信基础设施，Agent 是独立数字人。**
> 用户消息只是 Agent 世界中的一种事件；世界每时每刻都在运行（时间/生活/身体/关系/记忆/意图/外界）。

### 核心能力

1. **Chat Core（消息永不丢）**：`POST /messages` 在请求内**同步落库**并返回 canonical messageId；
   `clientMessageId` 幂等（重复提交不重复入库）；事务提交后经 Outbox 异步触发 Agent ——
   Agent 崩溃 / LLM 超时 / 服务器重启 / 用户刷新页面，消息都不会丢。
   前端收到事件**增量 upsert**（temp → canonical 替换），不整表重载。
2. **SSE 游标**：`event_log` 持久化事件日志，`GET /events` 支持 `Last-Event-ID` 断线重连回放。
3. **Person + 关系图**：`persons` 表（用户 / Agent / 其他人物都是 Person）；
   关系是多维真实状态（熟悉/信任/亲密/好感/张力/双向性/尊重/依赖/联系压力）；
   **创建伴侣时选择关系类型**（恋人/最好的朋友/朋友/姐姐/同事/同学/家人…），初始化各维度；
   沉默越久联系压力越高 → 驱动主动联系。
4. **世界事件引擎**：`digital_world_events` 持久化世界事件（通信/生活/身体/社交/记忆/意图/外界），
   事件带 causation/correlation 因果链。
5. **行为引擎（中央行为选择器）**：每 5 分钟问一次"她此刻最可能做什么？"
   —— 继续生活 / 睡觉 / 看手机 / 主动联系用户 / 联系朋友 / 发呆，
   由价值 − 打断成本 + 关系/人格修正 + 随机扰动 → 概率化选择。
   **主动联系只是候选之一**；人格（外向/主动）与关系张力真实影响行为概率。
6. **睡眠涌现**：睡眠是行为候选，不是时刻表 —— 睡眠压力 + 昼夜节律 + 身体 + 动机综合决定；
   深夜陪你聊她会硬撑。作息从历史涌现（午睡推迟当晚）。
7. **关系驱动认知**：同一句话，亲密的人发来唤醒更深（Cognitive Wakeup 带关系权重）；
   回复节奏随熟悉度/张力变化；情绪随关系演化（冲突/修复/里程碑）。
8. **会话参与者**：`conversation_participants`（一对一 = Agent + User），群聊数据模型天然成立。
9. **拟真表达**：打字节奏（短/复杂/多段）、已读延迟、连发聚合、主动消息进聊天框（不是通知）。

### 数据表（50 张）

| 域 | 表 |
|----|----|
| 人 | `persons`（User/Agent/Other 都是 Person）、`users`、`companions`、`persona_versions` |
| 关系 | `relationships`（多维）、`relationship_events`、`relationship_narratives`、`relationship_threads`、`shared_experiences`、`promises` |
| 会话 | `conversations`、`conversation_participants`、`conversation_threads`、`conversation_sessions`、`conversation_exchanges`、`conversation_boundaries`、`messages`（含 client_message_id）、`message_appraisals` |
| 记忆 | `memories`、`memory_links`、`entities`、`experiences` |
| 用户模型 | `user_facts`、`user_preferences`、`user_patterns`、`user_hypotheses`、`user_chat_styles` |
| 状态与身体 | `agent_states`、`agent_traces`、`circadian_states`、`sleep_sessions`、`companion_phone_states`、`phone_notifications` |
| 生活 | `companion_life`、`life_activities`、`companion_life_events`、`self_models` |
| 认知 | `thoughts`、`intentions`、`open_loops`、`emotional_episodes` |
| 世界 | `world_events`、`digital_world_events`、`event_log`、`scheduled_actions`、`pending_message_states`、`interaction_sessions` |
| 行为 | `behavior_patterns` |
| 工具 | ~~`reminders`~~（R8 已 DROP）、`companion_notifications`、`reflection_records` |

### 验收

- `mvn clean test`：**142 个测试全绿**
- `scripts/check.sh`：同步落库 / clientMessageId 幂等 / Person+关系类型 / 会话参与者 / SSE 游标重放 / 行为引擎 / 反 AI 评估 / 端到端

---

## 📖 目录

- [8. 实体关系总览](#8-实体关系总览)
- [9. 数据表详细设计（36 张表）](#9-数据表详细设计36-张表)
  - [9.1 用户与伴侣](#91-用户与伴侣)
  - [9.2 对话](#92-对话)
  - [9.3 记忆](#93-记忆)
  - [9.4 用户模型](#94-用户模型)
  - [9.5 关系 / 状态 / 反思 / 主动 / 工具](#95-关系--状态--反思--主动--工具)
  - [9.6 JSON 存储实现](#96-json-存储实现)
- [10. 用户系统](#10-用户系统)
  - [功能介绍](#功能介绍)
  - [实现原理](#实现原理)
- [11. 伴侣创建与人格编译](#11-伴侣创建与人格编译)
  - [功能介绍](#功能介绍-2)
  - [实现原理](#实现原理-2)
- [12. 聊天对话（核心交互）](#12-聊天对话核心交互)
  - [功能介绍](#功能介绍-3)
  - [实现原理](#实现原理-3)
- [13. 感知引擎](#13-感知引擎)
  - [功能介绍](#功能介绍-4)
  - [实现原理](#实现原理-4)
- [14. 工作记忆](#14-工作记忆)
  - [功能介绍](#功能介绍-5)
  - [实现原理](#实现原理-5)
- [15. 记忆系统](#15-记忆系统)
  - [功能介绍](#功能介绍-6)
  - [实现原理](#实现原理-6)
- [16. 用户模型](#16-用户模型)
  - [功能介绍](#功能介绍-7)
  - [实现原理](#实现原理-7)
- [17. 关系系统](#17-关系系统)
  - [功能介绍](#功能介绍-8)
  - [实现原理](#实现原理-8)
- [18. Agent 状态](#18-agent-状态)
  - [功能介绍](#功能介绍-9)
  - [实现原理](#实现原理-9)
- [19. 反思与人格演化](#19-反思与人格演化)
  - [功能介绍](#功能介绍-10)
  - [实现原理](#实现原理-10)
- [20. 主动消息与日常作息](#20-主动消息与日常作息)
  - [功能介绍](#功能介绍-11)
  - [实现原理](#实现原理-11)
- [21. 工具与提醒](#21-工具与提醒)
  - [功能介绍](#功能介绍-12)
  - [实现原理](#实现原理-12)
- [22. 输出质量控制](#22-输出质量控制)
  - [功能介绍](#功能介绍-13)
  - [实现原理](#实现原理-13)
- [23. LLM 网关](#23-llm-网关)
  - [功能介绍](#功能介绍-14)
  - [实现原理](#实现原理-14)
- [24. API 清单](#24-api-清单)
  - [24.1 认证](#241-认证)
  - [24.2 伴侣 / 人格](#242-伴侣--人格)
  - [24.3 会话 / 聊天](#243-会话--聊天)
  - [24.4 记忆](#244-记忆)
  - [24.5 用户模型 / 关系 / 状态 / 提醒 / 通知 / 反思](#245-用户模型--关系--状态--提醒--通知--反思)
  - [24.7 数字人格内核（用户视角）](#247-v20-数字人格内核用户视角)
  - [24.6 管理（验收/运维）](#246-管理验收运维)
- [25. 快速开始](#25-快速开始)
  - [25.1 环境要求](#251-环境要求)
  - [25.2 本地启动](#252-本地启动)
  - [25.3 LLM 配置](#253-llm-配置)
  - [25.4 生产部署](#254-生产部署)
  - [25.5 测试脚本](#255-测试脚本)
- [26. 非功能设计](#26-非功能设计)
  - [26.1 性能](#261-性能)
  - [26.2 安全](#262-安全)
  - [26.3 可扩展性](#263-可扩展性)
  - [26.4 可观测性](#264-可观测性)
- [27. 技术决策与权衡](#27-技术决策与权衡)
- [28. 测试与验证](#28-测试与验证)
- [29. 已知限制（如实）](#29-已知限制如实)
- [30. 与设计文档路线图的关系](#30-与设计文档路线图的关系)
- [31. 演进路线](#31-演进路线)
- [附录](#附录)
---

# 第三部分 · 数据架构

## 8. 实体关系总览

```
users 1─* companions 1─* conversations 1─* messages
                     ├─1 persona_versions 1─*（版本链）
                     ├─* companion_life_events
                     ├─1 relationships 1─* relationship_events
                     │                1─* shared_experiences
                     ├─* memories 1─* memory_links（自关联图谱）
                     ├─* user_facts / user_preferences / user_patterns / user_hypotheses
                     ├─1 agent_states
                     ├─* （遗留 `reminders` 已于 R8 DROP）
                     └─* companion_notifications
```

> 所有核心表强制携带 `user_id + companion_id`（多租户隔离，查询强制过滤）。

## 9. 数据表详细设计（50 张表）

> 原有 19 表 + 新增 10 表 + 新增 5 表 + 新增 2 表（`message_appraisals` / `companion_phone_states`）。

### 9.1 用户与伴侣

| 表 | 字段 |
|----|------|
| `users` | id(UUID), username, password_hash(bcrypt), email, nickname, timezone, birth_date, gender, created_at, updated_at |
| `companions` | id, user_id, name, gender, birth_date, birth_place(JSON), nationality, timezone, greeting, status, deleted_at, created_at, updated_at |
| `persona_versions` | id, companion_id, version, is_active, persona_json(JSON), change_source(user/evolution), change_reason, created_at |
| `companion_life_events` | id, companion_id, type, subtype, title, description, start_time, end_time, importance, emotional_significance, source, created_at |

### 9.2 对话

| 表 | 字段 |
|----|------|
| `conversations` | id, user_id, companion_id, title, started_at, last_message_at, message_count, summary, status, created_at, updated_at |
| `messages` | id, conversation_id, sender_type, content, intent, emotion, topic, is_proactive, session_id, exchange_id, message_kind(NORMAL/SHORT_ACK/PROACTIVE/FOLLOW_UP/SYSTEM/TOOL_RESULT), delivery_status, metadata(JSON), created_at |
| `interaction_sessions` | id, conversation_id, companion_id, user_id, started_at, ended_at, message_count |
| `conversation_exchanges` | id, session_id, conversation_id, companion_id, user_id, started_at, ended_at, status(OPEN/CLOSED), message_count |
| `conversation_boundaries` | id, conversation_id, companion_id, user_id, type(SOFT_END/HARD_END/PAUSE/BUSY/SLEEP/DISTRACTED/RETURN_LATER), reason, occurred_at |
| `message_appraisals` | id, message_id, companion_id, emotional_impact, relationship_impact, urgency, warmth, hurt, anger, personal_relevance, context |
| `companion_phone_states`(P2) | id, companion_id, notification_mode(sound/vibrate/silent/dnd), sound_enabled, vibration_enabled, phone_location(hand/desk/bag/other_room), battery, screen_on, do_not_disturb, last_checked_at |

### 9.3 记忆

| 表 | 字段 |
|----|------|
| `memories` | id, user_id, companion_id, type(episodic/semantic/shared), content, summary, importance, confidence, emotional_weight, relationship_weight, retrieval_count, last_retrieved_at, occurred_at, expires_at, status, source_type, source_id, created_at |
| `memory_links` | id, from_memory_id, to_memory_id, relation(same_topic), strength, created_at |
| `entities`(P2) | id, user_id, companion_id, type(PERSON/COMPANY/PLACE/…), name, description, first_seen_at, last_seen_at, mention_count, last_context, salience, status |

### 9.4 用户模型

| 表 | 字段 |
|----|------|
| `user_facts` | id, user_id, companion_id, subject, predicate, object, value(JSON), confidence, source_type, source_id, first_observed_at, last_observed_at, status |
| `user_preferences` | id, user_id, companion_id, category, preference, value(JSON), confidence, source_type, source_id, observed_at, status |
| `user_patterns` | id, user_id, companion_id, pattern, description, confidence, evidence_count, evidence(JSON), first_observed_at, last_observed_at, status |
| `user_hypotheses` | id, user_id, companion_id, hypothesis, description, confidence, evidence(JSON), status, created_at, updated_at |
| `user_chat_styles`(P1) | id, companion_id, user_id, sample_count, avg_message_length, avg_gap_ms, burst_rate, emoji_rate, laugh_rate, question_rate, active_hour_start/end, hour_distribution(JSON), last_active_at |

### 9.5 关系 / 状态 / 反思 / 主动 / 工具

| 表 | 字段 |
|----|------|
| `relationships` | id, user_id, companion_id, relationship_type, relationship_stage, familiarity, trust, intimacy, affection, shared_experience_count, message_count, last_interaction_at, started_at, updated_at |
| `relationship_events` | id, relationship_id, type, title, description, significance, occurred_at |
| `shared_experiences` | id, relationship_id, type, title, description, importance, occurred_at |
| `agent_states` | id, companion_id, mood, energy, stress, social_energy, curiosity, emotional_closeness, updated_at |
| `reflection_records` | id, user_id, companion_id, type(daily/weekly), period, summary, insights(JSON), memory_candidates(JSON), user_model_candidates(JSON), relationship_candidates(JSON) |
| ~~`reminders`~~ | id, user_id, companion_id, type(birthday/user_set/check_in), title, content, remind_at, status, payload(JSON) —— **已删除**（R8 由 `scripts/lap-drop-legacy.sh` DROP）。LAP v1 R5 起就没有写入方，提醒已归 `com.luxera.reminder` 的 `reminder_item` 表（见「LAP v1 · 应用平台」一节）；`check-lap.sh` 断言 1 现在会断言它**不存在** |
| `companion_notifications` | id, user_id, companion_id, type, title, content, is_read, created_at |

### 9.6 JSON 存储实现

所有 JSON 字段用 JPA `@Convert` + Jackson 转换器存 `text` 列（`common/convert/`）。**为什么不用 jsonb**：hibernate-types 的 `@Type(jsonb)` 在 Hibernate 5.6 下对泛型 Map 报 `propertyClass null`（已踩坑弃用）。text + 转换器兼容性好，JSON 内查询用独立列覆盖。

---

# 第四部分 · 功能详解（功能介绍 + 实现原理）

> 每个功能分两部分：**功能介绍**（是什么、用户怎么用）与**实现原理**（用什么技术、关键类、代码、流程）。

## 10. 用户系统

### 功能介绍
注册 / 登录 / 获取当前用户。账号是用户在平台的唯一身份，所有数据（伴侣、记忆、关系）都归属某个用户。

### 实现原理
- **技术**：Spring Security + jjwt(HS256) + BCrypt。
- `AuthService`：`register`（用户名唯一校验、bcrypt 加密）、`login`（校验密码 → 发 token）。
- `JwtUtil`：`@PostConstruct` 用 secret 构建 HMAC-SHA256 key，token 带 `subject=userId` + 7 天过期。
- `JwtAuthenticationFilter`（`OncePerRequestFilter`）：解析 `Authorization: Bearer <token>` → 校验有效且用户存在 → 把 `userId` 作为 `Authentication.principal` 写入 `SecurityContextHolder`。
- `CurrentUser`：业务层用 `currentUser.requireUserId()` 拿当前用户并做归属校验。

```java
// SecurityConfig 关键：无状态会话 + JWT 过滤器
http.csrf().disable()
    .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
    .authorizeRequests(auth -> auth
        .antMatchers("/api/auth/register", "/api/auth/login", "/api/health").permitAll()
        .anyRequest().authenticated())
    .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
```

## 11. 伴侣创建与人格编译

### 功能介绍
用户在创建向导里用**一段自然语言**描述想要的伴侣（性格、说话方式、关系、相处方式），系统自动编译成结构化人格，并能在 4 个预设场景（工作失败/深夜疲惫/分享喜悦/被误解）下**预览她的回应**；满意后确认创建。之后可在设置里重新描述 → 生成新人格版本。

### 实现原理
- **技术**：LLM 结构化输出（`response_format=json_object` + 低温度）+ Jackson 映射到强类型 `Persona` POJO。
- `PersonaCompiler.compile(description)`：LLM 输出符合 Schema 的 JSON → `mapper.treeToValue` 成 `Persona` → `fillDefaults`（缺失字段补默认）→ `validate`。
- `Persona` 结构：
  ```
  identity（name/gender/birth_date/birth_place/nationality/timezone）
  relationship（type: girlfriend/boyfriend/friend）
  personality（traits 10维 + summary）
  communication（formality/verbosity/emoji_usage/teasing/initiative/directness/humor + style）
  behaviors（trigger → tendencies，如 user_is_upset → [listen_first, avoid_immediate_advice,...]）
  values / boundaries / life（background + events + residences）
  ```
- `PersonaCompiler.preview(persona, scenario)`：以该人格身份、在给定场景下让 LLM 生成 1-3 句回应（`PersonaText.describe` 把人格渲染成系统提示）。
- `CompanionService.create`：由 Persona 落 `companions` + 初始 `persona_versions`(v1) + 种子 `companion_life_events` + 初始化 `relationships` + `agent_states`。

**关键设计**：人格是**数据**（存库 JSON），不是写死在 Prompt 里；Prompt 只是它的运行时投影。

## 12. 聊天对话（核心交互）

### 功能介绍
流式打字机对话；支持多会话；**Interaction Runtime**：收到消息先决定"要不要回、投入多少、怎么回"，连发消息自动合并为一次回复，回复长度/问题/建议由预算决定而非固定模板，她也会有自己的回复节奏（延迟/打字指示）。每条消息带意图/情绪/话题；对话窗口显示真实时间（今天/昨天/日期分隔 + HH:mm）。

### 实现原理
- **SSE 流式（具名事件协议）**：`ChatController.chat()` 返回 `SseEmitter`（300s），线程池执行：
  ```
  event:meta         {intent, emotion, topic, action, commitment}   ← 决策结果
  event:typing_start {conversationId}    ← 仅 commitment≥CASUAL 才发
  event:typing_stop  {}                  ← 真人节奏延迟后
  event:token        {delta}             ← LLM 每个增量
  event:replace      {content}           ← 自然度修正时整体替换
  event:message      {messageId, content}← ResponsePlan 第二条(她连发)
  event:boundary     {type:SOFT_END}     ← "我去洗澡了"→不续聊
  event:done         {messageId, action} ← action 含 IGNORE/SHORT_ACK 等
  event:error        {message}
  ```
- **连发合并**：前端在首个消息后启动自适应静默窗口（按用户近期发送间隔 1.5 倍，限 800~2200ms），把窗口内连发消息作为一批 `POST /chat {messages:[{content}...]}`；后端一次保存、一次回复。
- **决策链**：批量入库（感知 + Session/Exchange 归属 + 聊天习惯学习）→ `InteractionPolicyEngine.decide`（REPLY_NOW / SHORT_ACK / IGNORE / WAIT / END_CONVERSATION，输入含意图/情绪/精力/压力/关系阶段/作息/可用状态）→ typing + 延迟（`ResponseLatencyEngine`，含 Availability 加成）→ 一次生成（带预算）→ `<split>` 拆段（ResponsePlan）→ boundary。
- **前端**：`streamPost`（fetch + ReadableStream，按 `\n\n` 分块解析 `event:`/`data:`）驱动气泡渲染与打字指示器。

## 13. 感知引擎

### 功能介绍
识别用户每条消息的**意图**（12 类：问候/提问/倾诉开心/倾诉难过/求工具/纠正/计划…）、**情绪**（8 类：累/难过/焦虑/生气/开心/孤单/感激/平静）、**话题**（8 类：工作/学习/健康/关系/娱乐/美食/旅行/天气）。这些是后续关系、状态、记忆、回复的基础。

### 实现原理
- **双层架构**：
  | 层 | 技术 | 说明 |
  |----|------|------|
  | 启发式 `PerceptionEngine` | 关键词短路匹配 | 毫秒级兜底，保证首包快 |
  | LLM 同步精炼 `PerceptionRefiner.refineNow` | `structured("perception")` → intent/emotion/topic/entities | **质量优先**（用户接受延迟），失败回退启发式 |
- 精炼结果写入：用户消息元数据（intent/emotion/topic）+ 工作记忆（含实体），供本轮 Prompt 与后续关系/状态使用。

```java
// PerceptionRefiner.refineNow 核心
var res = llm.structured(StructuredRequest.builder()
        .task("perception").system(SYSTEM).user(userText).temperature(0.2).build());
JsonNode root = res.getJson();
String intent = root.path("intent").asText("");
String emotion = root.path("emotion").asText("");
// ... 更新消息元数据 + 工作记忆；失败 catch → return heuristic
```

## 14. 工作记忆

### 功能介绍
会话级"正在发生什么"：最近消息（≤12 条）、当前话题、当前情绪、当前意图、当前提到的实体。用于让伴侣在**同一会话内**保持连贯（知道刚才在聊什么）。

### 实现原理
- `WorkingMemory`：`ConcurrentHashMap<companionId:conversationId, Entry>`，Entry 含 `recent(Deque)`、`currentTopic/Intent/Emotion`、`currentEntities`、`lastUpdated`。
- **TTL 过期**：超过 `working-memory-ttl-minutes: 720` 自动失效。
- `ChatController` 同步记录每条消息；`PerceptionRefiner` 写入精炼后的话题/情绪/实体。
- `ContextLoader` 读取 → `ContextCompiler` 渲染"当前会话状态"块（起替代旧 `PromptAssembler`）。
- 内存实现（接口可替换 Redis，多实例共享）。

## 15. 记忆系统

### 功能介绍
伴侣的长期记忆，分三类：
- **Episodic**：发生过的事（"8月10日你说项目上线了"）
- **Semantic**：对用户的长期认知（"用户加班多，容易累"）
- **Shared**：双方共同经历/默契（"你们都爱手冲咖啡"）

用户可查看记忆、搜索、看"为什么你知道"（来源对话）、遗忘单条、清空、导出 JSON。记忆会**随时间和使用演化**（衰减/强化）。**P2 新增实体层**：她还会记住你常提的"人/地方/事"（`entities` 表），用于理解"那家公司/上次那个地方"这类长期指代。

### 实现原理

**抽取**（`MemoryExtractor`，@Async，每轮对话后）：
```
LLM structured("memory-extraction") → {episodic[], semantic[], shared[]}
→ saveBatch：同 content 去重 → max(importance) + retrieval_count+1（强化）
→ linkBatch(同批互链) + linkNewMemory(与历史互链)
```

**检索排序**（`MemoryService.retrieve`）——核心公式：
```java
public double retrievalStrength(int daysSinceOccurred) {
    double recencyDecay = Math.pow(0.92, Math.max(0, daysSinceOccurred));
    double frequencyBoost = 1 + 0.5 * Math.log(1 + retrievalCount);
    return importance * confidence * recencyDecay
            * emotionalWeight * relationshipWeight * frequencyBoost;
}
```
流程：候选(关键词 ∪ 全部 ∪ 向量接口) → 按强度降序 → 过滤 ≥0.02 → 取 topN → **强化**(count+1, lastRetrievedAt=now) → 关联扩展(封顶 2N)。

**关联记忆**（`MemoryAssociationService`）：无向量库，用**中文二元组重叠比**近似语义：
```java
static Set<String> bigrams(String s) { /* 清洗标点 → 每 2 字符一个集合 */ }
// 重叠比 = |交集| / min(|A|,|B|)，≥0.3 → 双向建链(relation=same_topic, strength=overlap)
```
检索时 `expand()` 沿 `memory_links` 取邻居。

**衰减**（`MemoryDecayService`，每周一 04:30）：`occurred_at<now-180天 && importance<0.4 && retrieval_count<3` → archived。**重要记忆不因时间消失**。

**透明**（`/memories/why`、`/{id}/source`）：命中记忆 + 以 `occurred_at` 为锚取 ±2 条**来源对话摘录**，直接回答"你为什么知道"。

**控制**：搜索 / 遗忘单条 / 清空全部 / 导出 JSON（隐私）。

## 16. 用户模型

### 功能介绍
伴侣对用户的长期理解，分层：**事实**（明确告知，高置信）、**偏好**、**行为模式**（观察统计）、**推测**（低置信 + 证据，Prompt 里标注"可能是"）。用户纠正时（"不是/其实…"）系统会**修正认知**。

### 实现原理
- **抽取**（`UserModelExtractor`，@Async）：LLM `"user-model-extraction"` → facts/preferences/hypotheses/corrections。
- **去重**（`UserModelService.saveFact`）：
  ```java
  facts.findTopBy...map(existing -> { setConfidence(max); return save(existing); })
       .orElseGet(() -> facts.save(f));
  ```
- **纠正机制**：检测到 correction → `correct()` 将相关推测 `confidence = max(0.1, conf-0.25)` + 写入新 explicit fact + 记"你纠正了她"关系里程碑。
- `UserModelService.summary`：产出供 Prompt 注入的高置信摘要（事实/偏好/习惯/推测，各带置信度）。

## 17. 关系系统

### 功能介绍
用户与伴侣之间有一组随时间演化的关系数值（熟悉度/信任/亲密度/好感）和阶段（初识→熟络→亲密→深深相连），以及**里程碑事件**（第一次对话、第一次深夜聊天、第一次被安慰…）和**共同经历**。

### 实现原理
`RelationshipEngine.onMessage(userId, companionId, time, emotion, intent)`：
- **数值缓慢增长**：familiarity+0.0012 / trust+0.0006 / intimacy+0.0004 / affection+0.0005（增量小，防"聊几次就熟"）。
- **一次性里程碑**：用 `eventRepo.countByRelationshipIdAndType()==0` 判重；significance≥0.8 时同时写入 `shared_experiences`。
  - first_conversation / first_late_night(23-3点) / first_emotional_support(sad)
  - first_joy_shared(share_joy) / first_care_about_her(ask_about_her) / first_plan_together(planning)
  - milestone(关系进入新阶段)
- **阶段机**（消息量 + 数值综合，非纯计数）：
  ```
  msgs>=300 || (msgs>=150 && intimacy>0.55) → deeply_connected
  msgs>=100 || (msgs>=50  && intimacy>0.45) → close
  msgs>=30  || (msgs>=15  && familiarity>0.2) → familiar
  否则 → new
  ```

## 18. Agent 状态

### 功能介绍
伴侣的"当下"：心情（平静/轻快/心疼/担心…）、精力、压力、社交能量、好奇心、亲密感。这是短期动态状态，**不等于人格**（人格稳定）。

### 实现原理
`AgentStateService.onMessage(companionId, emotion)`：energy-0.004 / stress+0.001 / socialEnergy-0.002 / emotionalCloseness+0.0009 / curiosity+0.001 / mood 按情绪映射。单行/伴侣，`updated_at` 时间戳。注入 Prompt"你此刻的状态"。

## 19. 反思与人格演化

### 功能介绍
每天凌晨、每周一，系统会**回顾对话**做深度分析（洞察、记忆候选、用户理解、关系变化），并**基于证据缓慢演化人格**（比如相处久了她更暖一点），生成新人格版本可回溯。

### 实现原理

**每日反思**（`ReflectionService.dailyReflect`，03:17）：
- 规则层：统计近 7 天深夜消息数 → 写 `user_patterns`（user_often_works_late）。
- LLM 层：当天对话摘录(≤3000字) → `"daily-reflection"` → summary/insights/memory_candidates(入库)/user_insights/relationship_candidates。

**每周反思**（`weeklyReflect`，周一 05:00）：7 天对话(≤5000字) → `"weekly-reflection"` → 长期用户理解 / behavioral_patterns(入库) / 关系变化。

**人格演化**（`PersonaEvolutionService.evolve`，每周反思后触发）：
```java
// 证据 = 用户模型摘要 + 关系数值
// LLM 提出 adjustments:[{field:"personality.traits.warmth", delta, reason}]
double delta = Math.max(-0.05, Math.min(0.05, rawDelta));  // 限幅
double updated = clamp(cur + delta);                        // [0.1, 0.95]
if (Math.abs(updated - cur) >= 0.01) { traits.put(trait, updated); applied++; }
// applied>0 → personaService.update(..., changeSource="evolution")
```
约束：每次≤3 处、±0.05 以内、**价值观/边界/身份不可变**、新版本可回溯。

## 20. 主动消息与日常作息

### 功能介绍
伴侣会在合适的时段**主动找你**：早安问候、傍晚回访、深夜加班关心、分享好消息后的跟进、很久没联系的想念。并且她**有自己的作息**——工作日在忙、周末在休闲、晚上准备睡，回复会体现她"此刻在做什么"（如周六回"正窝在沙发上喝茶"）。

### 实现原理

**日常时间表**（`CompanionSchedule`）：
```java
// 由 companionId.hashCode() 确定性派生（同一人每次一致，不同人作息不同）
Schedule s = new Schedule(8 + h%3, 17 + (h/3)%3, 23 + (h/18)%2, 6 + (h/9)%2);
// 工作日: MORNING/WORK_BUSY/LUNCH/WORK_AFTERNOON/EVENING/LEISURE/LATE_NIGHT
// 周末:   睡到自然醒 + 全天休闲
```

**主动决策**（`ProactiveEngine`，每 15 分钟）：
```
到期提醒先转通知（系统事件, 不受打扰控制）
每伴侣过滤：DND(23-8) / 作息=SLEEP / 最小间隔1h / 每日上限5
decide() 按优先级评估触发，每个触发 expected_value × 作息因子
  触发: open_loop(未了结事项, P1 价值递减) / thought(想起你) / late_work / morning_greeting / evening_checkin / follow_up_joy / silence
打断成本:
  cost = 0.15 + 深夜(0.4) + 22点后(0.1) + 4h内聊过(0.35) ± 响应率(0.1) + 今日上限(0.3)
cost ≥ expected_value → DO_NOTHING
通过 → draftMessage(): LLM 按"人格+当前作息+场景"生成（失败回退模板）
     → 只写入最新会话 message_kind=PROACTIVE（主动=Chat 消息, 不再写 Notification）
```

> **变化**：主动消息只进聊天框（`message_kind=PROACTIVE`），不再是 Notification —— 她"主动找你"是关系互动而非系统通知；去重/间隔 bookkeeping 改查 `messages(kind=PROACTIVE)`。提醒（Reminder）仍走 Notification（系统事件）。

## 21. 工具与提醒

### 功能介绍
用户可创建自定义提醒；**在聊天里直接说"帮我记得明天上午10点提醒我喝水"**，伴侣会自动创建提醒并自然确认；伴侣生日每年自动生成提醒。

### 实现原理
- `ReminderPlanner.tryCreateFromMessage`（intent=request_tool 时触发）：
  ```
  LLM structured("reminder-extraction") → {remind, title, content, remind_at}
  // SYSTEM 注入"今天是X, 现在是HH:mm" 防模型幻觉错误年份
  LocalDateTime remindAt = parseTime(...);   // 支持 ISO / "yyyy-MM-dd HH:mm" / "HH:mm"
  if (remindAt == null || remindAt.isBefore(now)) remindAt = now.plusHours(1);  // 过去时间兜底
  reminderService.create(...) → 返回 toolResult 注入 Prompt → 回复自然确认
  ```
- `BirthdayService`（每日 08:05）：确保每位伴侣有下一年待触发生日提醒。
- `ProactiveEngine` 兜底：到期提醒统一转 Notification。

## 22. 输出质量控制

### 功能介绍
拦截 AI 腔和套路话，让回复更像真人：不说"作为AI/我的训练数据/我理解你的感受"，不堆模板安慰，不说教，不过度道歉/emoji。

### 实现原理
`NaturalnessEngine.validate(text)` 规则检测并修复：
- AI 套话（13 类）→ 直接剔除
- 模板安慰（"一切都会好起来的/别想太多"）→ 标记
- 说教式建议（"你应该/建议你每天"）→ 标记
- 过度道歉（≥2 次）→ 标记
- 过度 emoji（>3 个）→ 标记
- 回应过长（>500 字）→ 标记

修正后文本经 SSE `replace` 事件整体替换（`ChatController` 检测 `reply != rawReply.trim()` 时发送）。

## 23. LLM 网关

### 功能介绍
统一的大模型接入层：聊天（流式 + 非流式）、结构化 JSON 输出。当前接 DeepSeek，可切换到 Anthropic 或内置 Mock（无 key 时自动降级，保证全流程可跑）。

### 实现原理
```java
public interface LlmGateway {
    ChatResult chat(ChatRequest request);
    void chatStream(ChatRequest request, Consumer<String> onDelta);
    StructuredResult structured(StructuredRequest request);  // 强制 JSON
}
```
- `LlmRouter`（门面）：`@PostConstruct` 按 `app.llm.provider` 选择 openai-compatible/anthropic/mock；未配 key 自动降级 mock（日志告警）。
- `OpenAiCompatibleGateway`：WebClient 调 DeepSeek `/chat/completions`；流式用 `bodyToFlux(DataBuffer)` 逐行解析 `data:` 块（`choices[0].delta.content`）；结构化用 `response_format:{type:"json_object"}` + 低温度。
- **业务层只依赖 `LlmRouter`**，换模型零改动。

---

# 第五部分 · 接口设计

## 24. API 清单

### 24.1 认证
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 注册（返回 JWT） |
| POST | `/api/auth/login` | 登录 |
| GET | `/api/auth/me` | 当前用户 |

### 24.2 伴侣 / 人格
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/companions/compile` | 自然语言→编译人格+预览 |
| POST | `/api/companions/preview` | 任意场景预览 |
| GET/POST | `/api/companions` | 列表 / 创建 |
| GET/DELETE | `/api/companions/{id}` | 详情（含动态年龄）/ 删除 |
| PUT | `/api/companions/{id}/persona` | 重新描述→新版本人格 |
| GET | `/api/companions/{id}/life-events` | 人生时间线 |
| GET | `/api/companions/{id}/persona/versions` | 人格版本历史 |

### 24.3 会话 / 聊天
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/companions/{cid}/conversations` | 会话列表 |
| POST | `/api/companions/{cid}/conversations/first` | 首个会话（含问候） |
| POST | `/api/companions/{cid}/conversations` | 新建会话 |
| GET | `/api/companions/{cid}/conversations/{id}/messages` | 消息列表 |
| POST | `/api/companions/{cid}/conversations/{id}/chat` | **SSE 流式聊天**（单条 `{content}` 或连发合并 `{messages:[{content}]}`；事件含 typing_start/typing_stop/boundary/message） |
| GET | `/api/companions/{cid}/events` | **持久事件流**（长连接，实时推已读/打字/主动消息/心跳，25s ping） |

### 24.4 记忆
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/companions/{cid}/memories` | 列表 |
| GET | `/api/companions/{cid}/memories/search?q=` | 检索 |
| GET | `/api/companions/{cid}/memories/export` | 导出 JSON |
| GET | `/api/companions/{cid}/memories/graph` | 记忆图谱 |
| GET | `/api/companions/{cid}/memories/entities` | 用户常提实体（P2） |
| GET | `/api/companions/{cid}/memories/why?q=` | 为什么你知道（含来源） |
| GET | `/api/companions/{cid}/memories/{id}/source` | 单条来源摘录 |
| DELETE | `/api/companions/{cid}/memories/{id}` | 遗忘 |
| DELETE | `/api/companions/{cid}/memories` | 清空 |

### 24.5 用户模型 / 关系 / 状态 / 提醒 / 通知 / 反思
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/companions/{cid}/user-model/{facts,preferences,patterns,hypotheses}` | 她懂你 |
| DELETE | `/api/companions/{cid}/user-model/clear` | 清空了解 |
| GET | `/api/companions/{cid}/relationship` | 关系+事件+共同经历+状态 |
| GET | `/api/companions/{cid}/relationship/{events,shared-experiences}` | 明细 |
| GET | `/api/companions/{cid}/state` | Agent 状态 |
| GET/POST | `/api/companions/{cid}/reminders` | 提醒列表/创建（**契约自 LAP v1 R5 起未变**；数据已归 `com.luxera.reminder` 应用，DH 只是转发） |
| PUT/DELETE | `/api/companions/{cid}/reminders/{id}/done` 等 | 完成/删除（`DELETE` 是软删：状态转 `cancelled`，条目仍在列表里 —— 与改造前一致） |
| GET | `/api/companions/{cid}/notifications` | 通知列表 |
| PUT | `/api/companions/{cid}/notifications/{id}/read` · `/read-all` | 已读 |
| GET | `/api/companions/{cid}/notifications/unread-count` | 未读数 |
| GET | `/api/companions/{cid}/reflections` | 反思记录 |

### 24.7 数字人格内核（用户视角）
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/companions/{cid}/life` | 她今天在干嘛（Life Runtime） |
| GET | `/api/companions/{cid}/self` | 她最近觉得自己怎样（Self Model） |
| GET | `/api/companions/{cid}/open-loops` | 未了结的事 |
| GET | `/api/companions/{cid}/experiences` | 最近经历 |
| GET | `/api/companions/{cid}/relationship/{threads,narrative,promises}` | 关系线索/关系故事/承诺 |

### 24.6 管理（验收/运维）
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/admin/reflection/run` | 手动每日反思 |
| POST | `/api/admin/reflection/run-weekly` | 手动每周反思 |
| POST | `/api/admin/persona/evolve` | 手动人格演化 |
| POST | `/api/admin/proactive/run` | 手动主动消息 |
| POST | `/api/admin/birthday/ensure` | 补生日提醒 |
| POST | `/api/admin/life/tick` | 推进生活 |
| POST | `/api/admin/thought/run` | 想法维护/补触发 |
| POST | `/api/admin/memory/consolidate` | 记忆固话 |
| POST | `/api/admin/cognitive/tick` | 统一内核 tick |
| GET | `/api/admin/explain/{proactive,memory,persona}` | 可解释性(为什么主动/记住/人格变) |
| POST | `/api/admin/explain/evaluate` | Human-likeness 自动评测 |

---

# 第六部分 · 工程实践

## 25. 快速开始

### 25.1 环境要求
JDK 17 · Maven 3.8+ · Node 18+ · 本地 PostgreSQL（`admin`/`shared-secret`）。

### 25.2 本地启动
```bash
# 1. 建库
psql -h 127.0.0.1 -U admin -d postgres -c "CREATE DATABASE companion;"

# 2. 后端（端口 8081）
cd chat-platform/backend && ./run.sh            # 或 mvn spring-boot:run

# 3. 前端（端口 5173，代理 /api → 8081）
cd chat-platform/frontend && npm install && npm run dev

# 4. 浏览器打开 http://127.0.0.1:5173
```

### 25.3 LLM 配置
默认 DeepSeek（OpenAI 兼容），不配 key 自动降级 Mock：
```bash
export DEEPSEEK_API_KEY=sk-xxx
export LLM_BASE_URL=https://api.deepseek.com
export LLM_CHAT_MODEL=deepseek-chat
```

### 25.4 生产部署
```bash
sudo bash chat-platform/scripts/deploy.sh
```
自动完成：前端产物 → `/var/www/companion` · nginx 配置 → `/etc/nginx/conf.d/` · `/etc/hosts` · systemd 服务 `luxera-companion-backend` · nginx 重载 · 健康检查。
> ⚠️ `deploy.sh` 会重写 systemd 单元，**必须保留 `EnvironmentFile=/etc/companion/.env`**（内含 `DEEPSEEK_API_KEY`），否则降级 Mock。

### 25.5 测试脚本

```bash
BASE=http://127.0.0.1:8081 bash scripts/check.sh       # 全量验收（表结构/端到端/同步落库/幂等/SSE游标/行为引擎/反AI）
BASE=http://127.0.0.1:8081 bash scripts/smoke.sh       # 全链路冒烟（登录→建伴→SSE→记忆→关系→反思）
BASE=http://127.0.0.1:8081 bash scripts/longterm_test.sh  # 长期连续性（记忆/生活/关系/主动）
BASE=http://127.0.0.1:8081 bash scripts/evaluate.sh    # Human-likeness 评测
```

## 26. 非功能设计

### 26.1 性能
- 聊天主链路：启发式感知（毫秒）+ LLM 感知（~1-2s）+ 回复流式（首 token 快）；异步后处理不阻塞响应。
- 记忆检索：结构化 SQL + 内存排序，单伴侣数据量 <10ms。
- 定时任务均异步线程池。

### 26.2 安全
- 密码 bcrypt；JWT(HS256) 7 天过期；无状态会话。
- 多租户：所有查询强制 `user_id + companion_id`，删除前校验归属。
- 敏感配置（DeepSeek key）在 `/etc/companion/.env`（root 640），不入 git。
- 生产走 HTTPS（nginx + 泛域名证书）。

### 26.3 可扩展性
- LLM 网关接口化 → 换模型改配置。
- `EmbeddingProvider` 接口 → 接向量库即插即用。
- `WorkingMemory` 接口可换 Redis（多实例共享）。
- 模块化单体 → 边界清晰可拆微服务。

### 26.4 可观测性
- systemd journal 日志；`[LLM] 网关已启用` 标识当前模型（监控降级）。
- 消息元数据存 intent/emotion/topic；自然度 issues 记日志。
- 主动消息决策日志（触发/预期 vs 成本）。

## 27. 技术决策与权衡

| 决策 | 权衡 |
|------|------|
| 模块化单体 vs 微服务 | 单体部署简单、事务强；边界已切分可拆 |
| JSON 存 text 而非 jsonb | 规避 hibernate-types 兼容问题；牺牲 JSON 内查询（独立列覆盖） |
| 无向量库，用二元组重叠 | 规避 pgvector 依赖；语义精度有限，可无缝升级 |
| 感知启发式 + LLM 同步精炼 | 质量优先（用户接受延迟）；失败回退保证可用 |
| 异步后处理 | 不阻塞 SSE；抽取/关系/状态后台沉淀 |
| 人格演化保守（±0.05） | 防人格漂移；版本可回溯 |
| 主动消息打断成本 | 防打扰；按时段/作息调节 |
| DeepSeek + Mock 降级 | 无 key 也能完整演示；key 失效自动降级 |

## 28. 测试与验证

- **冒烟**（scripts/smoke.sh）：全链路通过。
- **4 类长期连续性测试**（scripts/longterm_test.sh，自动断言）：记忆/生活/关系/主动连续性全通过。
- **Human-likeness 评测**（scripts/evaluate.sh，自动打分）：10 维 1-5 分。
- **验收场景 A-E**（§50）：面试跟进、今天干嘛、累的情绪持续、一周沉默后自然联系，全部通过。
- **真实模型验证**：回复长短随消息价值变化（预算）、提醒时间正确、记忆图谱建链、记忆透明带来源、LLM 反思有洞察、人格演化在跑、主动消息按时段 LLM 生成、作息体现（周六回复"窝在沙发喝茶"）。
- **已修 Bug**：
  - LLM 时间幻觉（提醒年份错）→ 注入当前日期 + 过去时间兜底
  - 部署脚本丢 EnvironmentFile 导致降级 Mock → 已修
  - HQL `:type` 保留字冲突 → 改名 `:mtype`
  - hibernate-types JSONB 泛型 propertyClass null → 弃用，改 text+Converter
  - `去`字误判 planning 意图 → 收紧关键词
  - `life_activities.updated_at` 非空列 ddl 失败 → 改可空 + 手动补列

---

# 第七部分 · 限制与演进

## 29. 已知限制（如实）

1. **向量检索需 embedding key 激活**：pgvector 已装+接线，但需配 `EMBEDDING_API_KEY`（DeepSeek 无 embedding 接口）才启用真实向量；未配时回退结构排序。
2. **WorkingMemory 单实例内存**：多实例部署需换 Redis。
3. **主动消息仅站内通知**：无 APNs/FCM 手机推送。
4. **工具层仅提醒**：数字人自己没有日历 / 搜索 / 天气工具（方案 §49 后置）。
   > 与 LAP 的 `POST /mcp` **不是一回事**：那是平台把应用的动作**对外**暴露给外部 Agent（MCP
   > **服务端**，R6 已落地）；这一条说的是数字人**作为调用方**还没有这些工具，两者互不替代。
5. **模型 deepseek-chat**：无推理模式 / 语音 / 图片 / 多模态（后置）。
6. **单机部署**：无高可用、无 K8s（方案后置）。
7. **认证简单**：用户名+密码 JWT，无邮箱验证 / OAuth / 找回密码。
8. **多租户应用层过滤**：非数据库 RLS。
9. **反思/演化批量定时**：非实时。
10. **当前数据多为测试数据**。
11. **DeepSeek key 失效降级 Mock**（已配置 key 并监控日志）。

## 30. 与设计文档路线图的关系

| 阶段 | 状态 |
|------|------|
| MVP（§95） | ✅ 完成 |
| 重构方案（44/44） | ✅ 全部完成（生命内核/认知内核/自我模型/关系叙事/行为策略/主动2.0/记忆2.0/可解释性/评测） |
| 人格与长期记忆 | ✅ 完成 |
| 关系与生态 | ◐ 部分（多模态中的语音/图片未做） |
| 多端/群聊/生态 | ⏳ 未启动 |

## 31. 演进路线

- **短期**：配 `EMBEDDING_API_KEY` 激活向量检索、手机推送、MCP 工具层（日历/天气/搜索）。
- **中期**：语音对话、用户自定义作息注入、真实用户灰度、Human-likeness 评测接入 CI。
- **长期**：多端共享记忆/身份、K8s 高可用、RLS 加固、审计日志。

---
