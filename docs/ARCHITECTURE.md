# chat-platform 架构说明（V10 多模块）

> 面向运维与二次开发。README 讲"做了什么"，本文讲"东西在哪、为什么这么切、怎么验证边界没被破坏"。

---

## 1. 三个系统，六个 Maven 模块

V10 的世界观是三个系统：

```
   ┌──────────────┐   消息/事件（外部世界）   ┌──────────────────┐
   │ Chat Platform│ ◄──────────────────────► │ Digital Human    │
   │  （软件）     │   DHCP v1 / WebSocket    │  （"人"）         │
   └──────────────┘                          └──────────────────┘
          ▲                                          ▲
          │            contracts（共同语言）           │
          └──────────────────────────────────────────┘
                                                     ▲
                                      ApplicationRuntimePort / ApplicationEventSink
                                                     │
                                          ┌──────────────────────┐
                                          │ Application Platform │
                                          │ （应用；真人与 Agent  │
                                          │   共用同一套 Action） │
                                          └──────────────────────┘
```

数字人**只通过 `contracts` 里的两个端口**看见应用 —— 它不认识任何一个具体应用，也不认识承载它们的
模块。这条单向门是 LAP v1 的核心约束。

物理上落在 `backend/` 的六个模块：

| 模块 | 类型 | 拥有的顶层包（`com.luxera.companion.` 之下） | 说明 |
|---|---|---|---|
| `contracts` | library jar | `contracts` | 纯 DTO / enum / SPI 端口。**无 Spring、无 JPA** |
| `platform-kernel` | library jar | `auth` `common` `config` `outbox`（+ 根下 `HealthController` `ApplicationContextProvider`） | 各平台都要用的最小内核 |
| `chat-platform` | library jar | `conversation` `event` `simulator` | 会话、消息、SSE、Simulator WS **服务端** |
| `digital-human-platform` | library jar | `agent` `appraisal` `attention` `behavior` `cognition` `cognitive` `digitalhuman` `emotion` `eval` `experience` `intention` `interaction` `life` `llm` `memory` `openloop` `person` `persona` `phone` `plan` `proactive` `reality` `reflection` `relationship` `runtime` `selfmodel` `sleep` `state` `thought` `tool` `usermodel` `world` | 数字人的全部认知 / 生活 / 记忆 / 状态 + WS **客户端** |
| `application-platform` | library jar | `application` | 应用的宿主：manifest（含 `ui` 段）、能力/应用/动作发现、Resource 统一读模型、Action 网关、参与者/权限/会话与邀请、生命周期与 §4.1 可用性投影、内置参考应用、MCP 适配器 |
| `bootstrap-app` | **可执行 jar** | 无（只有启动类与测试） | 唯一同时看得见三方的模块；`spring-boot-maven-plugin:repackage` 只在这里开 |

六组包两两不相交 —— 这既是为了边界清晰，也是 Java 的硬性要求（split package 会让两个模块的同名包在
classpath 上静默合并）。`scripts/check-v10.sh` 第 1 步就是自动化检查这件事。

> **`contracts` 与 `application-platform` 的分界规则**：*contracts 放「平台的调用方」需要的东西，
> application-platform 放「应用的作者 / 宿主」需要的东西*。所以 `ActionSpec` / `ResourceView` /
> `PrincipalType` 这些要用来拼 LLM prompt 的类型在 contracts；`ApplicationManifest` /
> `ManifestParser` / `ManifestValidator` 这些解析与校验在 application-platform —— 数字人没有理由
> 去校验别人的 JSON Schema。

### 三个参考应用（一个迁移 + 两个新增）

`application-platform` 里内置了三个应用，它们**不是**"平台自带的功能"，而是三份用来证明平台成立的
样本。每个都只由三样东西组成：一份 `application-manifest.json`、一个 `LapApplicationModule` 实现
（注册 action handler）、以及它自己的存储（或干脆不存）。

| 应用 | 能力 | 性质 | 它证明什么 |
|---|---|---|---|
| `com.luxera.tictactoe` | `game.play` | 从 DH 迁过来的 | 一个应用可以不认识数字人；棋盘**就是**一条 Resource，不建表 |
| `com.luxera.gomoku` | `game.play` | 新增 | 第二个同域应用。它的 action id 与井字棋**一模一样**（`game.make_move` 等），只有 URI scheme 不同 —— 所以 handler 注册表的键必须是 `(applicationId, version, actionId)`，用 `Map<String, …>` 会让后注册的覆盖先注册的 |
| `com.luxera.reminder` | `reminder.manage` | 新增 | 跨能力域；一个把状态放在**自己表里**的应用（`backing: APP_OWNED` + `ResourceProjector`），证明平台允许"会话型资源"与"主体型资源"两种锚点 |

> `reminder` 的清单里**没有** `ui` 段 —— `ui` 是可选的（默认值在 `SurfaceCatalogue` 里现算，不烘进解析结果），
> 这样一个面向 Agent 的应用可以没有界面，而"作者没写"与"作者写了默认值"在版本 diff 里仍然分得开。

「加一个新应用 = 一份 manifest + 一个 handler 注册，DH 一行不改」这句话的验收方式，就是这三个应用：
五子棋落地时 `digital-human-platform` 的改动为零。

### 为什么 bootstrap-app 不直接叫 chat-platform

`bootstrap-app` 里没有任何业务代码，只有一个 `@SpringBootApplication`（`CompanionApplication`，
位于根包 `com.luxera.companion`，所以组件扫描 / 实体扫描 / Repository 扫描自动覆盖三个平台）
加上 `spring-boot-maven-plugin` 的 repackage。把它单列出来，是为了让"平台本身"和
"把平台拼起来跑"这两件事在构建层面就是分开的 —— 将来要拆两个进程，删掉 `bootstrap-app`、
各加一个 launcher 即可，三个平台模块一行不用改。

---

## 2. 边界靠什么保证（不是靠自觉）

依赖方向：

```
chat-platform            ──► contracts        （禁止 ──► digital-human-platform / application-platform）
digital-human-platform   ──► contracts        （禁止 ──► chat-platform / application-platform）
application-platform     ──► contracts        （禁止 ──► chat-platform / digital-human-platform）
platform-kernel          ──► （无平台依赖）
bootstrap-app            ──► chat + DH + application + contracts   （只组装，不产生跨平台依赖）
```

三个平台两两不相见，各自只认识 `contracts`。**应用事件进入数字人的唯一通道是 DH 侧实现
`ApplicationEventSink`** —— 单向门，反方向没有对应的端口。

三重守卫，缺一不可：

| 守卫 | 位置 | 性质 | 何时跑 |
|---|---|---|---|
| 包归属互斥 + 源码引用 + pom 依赖图 | `scripts/check-v10.sh` | grep（快速第一道） | 提交前 / CI，`mvn compile` 之前就能发现问题 |
| contracts 自足性（白名单） | `contracts/src/test/java/.../architecture/ArchitectureTest.java` | ArchUnit | `mvn test` |
| 三方互不依赖 | `bootstrap-app/src/test/java/.../architecture/ModuleBoundaryArchitectureTest.java` | ArchUnit（看字节码） | `mvn test` |
| 生态四层护栏 §115–§118 | `bootstrap-app/src/test/java/.../architecture/LapEcosystemArchitectureTest.java` | ArchUnit | `mvn test` |

**R15 补的第四条守卫**守的是应用生态自身的四条不变量（§115–§118），与上面三条"模块之间"
的规则互补 —— 它们守的是"同一模块里哪一层不许碰哪一层"：

| 条款 | 不变量 | 症状（守不住会长什么样） |
|---|---|---|
| §115 | chat-platform 不认识任何内置应用 | 会话代码里长出 `if (app == gomoku)`，加第二个游戏要改聊天 |
| §116 | 动作执行永远经 `ActionGateway` | web 层直接 new 一个 Handler，幂等/授权/审计全部绕开 |
| §117 | 对 `ResourceRepository` 的写只在 resource 与 repository 两个包 | 业务代码直接 `save` resource 行，版本/CAS 悄悄失效 |
| §118 | `AgentRuntime` 对应用平台的依赖被禁 | Agent 绕过 Gateway 调应用，"Agent 没有 Application 专用 API"变成空话 |

四条规则的非空性是**注入探针证过的**：往 web 与 chat 各写一个违规类，看四条规则各自炸出真违规再删掉
变绿 —— 一条从不失败的守卫等于没有守卫。§116 的写法值得记住：**按类名禁 handler 那一族，外加
"Controller 必须依赖 Gateway"的正面断言** —— 只禁不证，规则改对了也看不出来（第一版按包禁，把
合法的 `Controller → ActionGateway` 也禁了：Gateway 自己就住在 action 包里）。

**为什么 ArchUnit 规则要放在两个不同的模块**：

- `contracts` 的 classpath 上只有它自己 —— 所以那里能检查的是**结构性自足**：一旦有人往 contracts 的
  pom 里加了平台依赖，那些类立刻出现在 classpath 上，白名单规则失败。
- "chat 不得引用 DH、DH 不得引用 application" 需要**同时看得见三方**，只有 `bootstrap-app` 做得到。

**规则里的包名必须写全限定前缀**（`com.luxera.companion.conversation..`，不能写 `..conversation..`）：
chat 与 DH 各自都有叫 `conversation` / `event` / `state` / `memory` 的包，通配写法会把 DH 自己的
`digitalhuman.conversation` 也算进去，规则立刻变成几百条假阳性，最后只能被删掉 ——
那是最坏的结果：一个看起来在守边界、其实什么都守不住的空壳。这一点在
`ModuleBoundaryArchitectureTest` 的类注释里也写了一遍，改之前请先读。

**顶层包之间的循环依赖在这个 codebase 里是既成事实**（拆分前就存在，与本次拆分无关），
所以 `ModuleBoundaryArchitectureTest` **不做**顶层包循环断言。模块层面的依赖方向由上面三条保证。

---

## 3. 跨平台通信：六个 SPI 端口

`contracts.spi` 是唯一的跨平台 Java 接口层。两边各写各的适配器，互相不认识对方的实现类。

| 端口 | 方向 | 实现方 | 位置 |
|---|---|---|---|
| `ChatWorldPort` | DH → Chat | 聊天平台 | `chat-platform` : `com.luxera.companion.conversation.ChatWorldAdapter` |
| `CompanionDirectoryPort` | Chat → DH | 数字人平台 | `digital-human-platform` : `com.luxera.companion.runtime.CompanionDirectoryAdapter` |
| `SimulatorAccessPort` | DH → Chat | 聊天平台 | `chat-platform` : `com.luxera.companion.simulator.server.SimulatorAccessAdapter` |
| `ApplicationRuntimePort` | DH → Application | 应用平台 | `application-platform` : `com.luxera.companion.application.action.ActionGateway` |
| `ApplicationEventSink` | Application → DH | 数字人平台（**单向门**） | `digital-human-platform` : `com.luxera.companion.digitalhuman.event.DhApplicationEventSink` |
| `ApplicationCatalogPort` | Chat → Application | 应用平台 | `application-platform` : `com.luxera.companion.application.port.ApplicationCatalogAdapter` |

> 前三个端口各家都写了一个 `*Adapter`；**`ApplicationRuntimePort` 没有** —— 它的实现类就是
> `ActionGateway` 本身。这不是漏了个名字，而是这个端口与其余三个不同：它不是"把本地 Bean 包一层
> 好让对面看不见"，而是平台*唯一*的动作入口（真人 REST、MCP 适配器、DH 进程内调用都从这里过）。
> 再包一个 `ApplicationRuntimeAdapter` 只会多一层什么都不做的委派。
> （曾有一份设计文档写成 `...runtime.ApplicationRuntimeAdapter`，那个类从来没有存在过。）

最后一个（R12 加的）是这三个端口里唯一不属于数字人的：它让**聊天平台**能在不认识应用平台的前提下
"在这段对话里开一个应用"。它存在的理由不是"多一个抽象更优雅"，而是两个既有守卫同时成立时唯一的出路：
`check-v10.sh` 的 pom 禁令不许 `chat-platform` 依赖 `application-platform`，而 §63 又要求聊天里
能发现/启动/邀请应用。三件事同时为真，中间就必须隔一个契约端口。它的 DTO 全在 `contracts.chat`
（不在 `contracts.application`）：那些形状是**聊天协议**的一部分，不是应用协议的一部分 ——
`ApplicationCard` 只说"能不能开"，它甚至不是应用详情。

后两个 LAP 端口（`ApplicationRuntimePort` / `ApplicationEventSink`）是 DH 与应用的**全部**接触面
（`ApplicationCatalogPort` 面向的是聊天，不是数字人）。它们合起来只允许两件事：

- 数字人**读**统一读模型（`read(uri)` → `ResourceView`）、**问**现在能做什么
  （`pendingActions(uri, ctx)` → `List<ActionSpec>`）、**做**（`execute(ActionRequest, ctx)`）。
  数字人自己不解析任何应用状态、不判断任何业务规则 —— 棋盘长什么样、轮到谁，都由应用回答。
- 应用在事务提交之后**通知**数字人"有事发生了"。事件 id 由应用按 manifest 里的 `idTemplate`
  确定性铸造（如 `game://session/{id}#MOVE-0`），DH 侧的去重才有意义；否则重试会让 Agent
  对同一步行动两次。

DH 侧挂在 `digitalhuman.event.EventRouter` 的 `APPLICATION_EVENT` 上有**三个**消费者，看的是同一条
事件的三个侧面（`EventRouter.subscribe` 是追加语义，所以它们互不遮蔽）：

| 消费者 | 它问的问题 |
|---|---|
| `runtime.AgentApplicationFlow` | 「我该做点什么」—— 八段流水线（R13 起）→ 读 Resource → 问能做什么 → 执行并记入现实账本，全程跑在 `PersonActorRegistry.tell(personId, …)` 的 per-person 串行邮箱里；它同时也是**主动方向**的入口（`route()`，见「Agent 的 LLM 契约」）。邀请事件在它入口处早退 —— 那不是"应用里发生了什么" |
| `runtime.application.AgentApplicationInvitationHandler` | 「有人点名找我吗」——（R13 加）只看载荷里 `eventType=APPLICATION_INVITATION` 的那一类，问 LLM 去不去，去就兑票进场。见「数字人怎么参与一场会话」 |
| `digitalhuman.event.ApplicationNotificationBridge` | 「该不该说给他听」—— 事件载荷里带 `notify` 块就落一条 `companion_notifications`，`type` 原样透传 |

第二条路是 R5 加的，它让「提醒到点」不再需要一个 DH 侧的扫描器：`ReminderDispatchJob` 在应用侧
到点发事件，是否变成用户看得见的通知由 DH 决定。这个类里没有一个字提到提醒 —— 它认识的只是
`notify` 这个**形状**。

> **LLM 优先、绝不降级到启发式**：LLM 不可用（或处于 mock）时 `AgentApplicationFlow` 直接返回，
> 不落子、不随机、不"取第一个空格"。`AgentApplicationFlowTest` 断言此时 `execute()` 调用次数为 0 ——
> 这条断言是这条性质在整个重构过程中的保险丝。

### LAP v2 的协议面（`/api/v1`，R9–R11 起；聊天侧那一面见下一节）

真人和 Agent 走的是**同一条**路，协议里不存在"Agent 专用接口"：

```
GET  /api/v1/capabilities                        能力目录（发现链第 1 级）
GET  /api/v1/capabilities/{capabilityId}/applications   候选应用（第 2 级）
GET  /api/v1/applications                        全部在架应用（应用市场那一页）
GET  /api/v1/applications/{applicationId}        应用详情：十态 status + §4.1 可用性 + ui 段
GET  /api/v1/applications/{applicationId}/actions       动作 + agentHint（第 3 级）
POST /api/v1/applications/{applicationId}/sessions      打开应用 = 开一场会话（§16 形状）
GET  /api/v1/sessions  /  GET /api/v1/sessions/{id}  /  DELETE /api/v1/sessions/{id}
POST /api/v1/sessions/{id}/participants          加入（幂等；只认 role，身份取自凭据）
GET  /api/v1/sessions/{id}/participants          这一场里有谁（含已离场者，各带 status）
DEL  /api/v1/sessions/{id}/participants/me       自己走（会话不因此结束）
POST /api/v1/sessions/{id}/invitations           铸一张邀请票（响应里的 token 只出现这一次）
GET  /api/v1/sessions/{id}/invitations           这个会话发过的票（只有主人）
DEL  /api/v1/invitations/{invitationId}          收回一张票
POST /api/v1/join/{token}                        兑票进会话（公开端点，持票即入）
POST /api/v1/subscriptions                       会话之内的事件订阅
GET  /api/v1/resources?uri= | ?sessionId= | ?applicationId=   统一读模型（只读，无幂等键）
POST /api/v1/actions:execute                     唯一的动作入口（Canonical；/actions/execute 是别名）
PATCH /api/v1/applications/{id}/status           生命周期状态机（只有平台自己是演员）
PUT  /api/v1/applications/{id}/versions/{v}/manifest     写一份新版本的清单
```

> **`POST /api/v1/applications/{id}/install` 已经不存在了。** v2 里应用不需要"装"，也不需要
> "卸" —— 打开就是开一场会话，结束会话不等于卸载应用。这条路径的消失是 §130 原则 1 的落点，
> `check-lap.sh` 里有一条断言专门守着它（加回任何一个"安装"入口，那条立刻红）。

### 把应用带进一段对话（R12 起，聊天平台的三个端点）

应用平台那一面（上面那张表）回答的是"这个应用是什么、这一场里有谁"；它**不回答**
"在我和小满的这段对话里，我们能一起玩点什么"。后者只有聊天平台答得了，所以它自己开了三个端点 ——
注意路径前缀是 `/api/companions/{c}/conversations/{v}`，与 `/api/v1` 是**两个模块的两套面**，
不是同一批数据的两条路：

```
GET  /api/companions/{c}/conversations/{v}/applications
       这段对话里能开什么、已经开着什么 → { openable: [ApplicationCard], open: [ApplicationSessionView] }
POST /api/companions/{c}/conversations/{v}/applications
       在这段对话里开一个应用 → 201 { session, participant, messageId }
       被拒时(下架/不允许新会话)**什么都不留下** —— 先开应用、再落卡片消息
POST /api/companions/{c}/conversations/{v}/applications/{sessionId}/share
       把加入链接作为一条消息发出去 → 201 { invitationId, token, joinUrl, role, maxUses, messageId }
```

**为什么聊天平台不直接调 `/api/v1`。** 它在编译期看不见应用平台 —— `check-v10.sh` 的 pom 禁令与
`ModuleBoundaryArchitectureTest` 六条规则都不许。所以中间隔着一个契约端口：

```
chat-platform  ──►  contracts/spi/ApplicationCatalogPort  ◄──  application-platform
   (三个端点)              (契约, DTO 全在 contracts.chat)        (ApplicationCatalogAdapter)
```

端口上每个方法都要一个 `InvocationContext`：进程内调用的信任模型是"**认证已经由聊天侧做完，
但授权仍由应用平台执行**"，所以身份显式写下来（`HUMAN(userId)` + 一个 correlationId），
而不是省掉 —— 一个省略身份的进程内调用会让应用平台无从判断"你能不能开这一场"。
`ChatTestApplicationCatalog` 里那个假实现也会拒掉没有 `principalType` 的上下文，
与真的 `InternalPrincipalResolver` 同形：测试不该在一个比生产宽松的世界里变绿。

失败跨过这条边界时要脱一层壳：应用平台抛 `SessionException`，适配器翻成
`ApplicationCatalogException`（保住 code / message / `ActionStatus`），聊天侧的
`ConversationApplicationExceptionHandler` 再把 `ActionStatus` 翻成 HTTP。三跳任何一跳断了，
客户端都会拿到 500 —— `check-lap.sh` 断言 19 里有一条"开一个不存在的应用要回 404
`UNKNOWN_APPLICATION`"专门守这个。

### 应用卡片就是一条消息（§66）

```json
{
  "id": "msg_xxx",
  "senderType": "system",
  "messageKind": "APPLICATION_CARD",
  "content": "「井字棋」已在这段对话里开启",
  "metadata": { "applicationId": "com.luxera.tictactoe", "sessionId": "sess_xxx",
                "name": "井字棋", "role": "OWNER", "status": "ACTIVE" }
}
```

没有 `application_card` 表。卡片和别的消息排在同一条时间线上、进同一个 `messageCount`、
走同一套分页 —— 另建一张表会立刻带来"卡片和消息谁先出现"这种没有答案的问题。
`content` 同时写一句人能读的话：认不出这个 `messageKind` 的客户端会把它当普通消息显示，
而那正是它该做的降级。

落这类消息走 `ConversationService.addMessage` 而不是 `MessageCoreService.send`：
后者会唤醒数字人，而"井字棋已开启"是**平台通告**，把它喂给 LLM 只会让它对着一段系统文本
编一句回复。数字人要知道这一局开起来了，走的是应用事件那条路（`APPLICATION_*` 家族）。

### 打开一个应用返回什么（§16）

```json
{
  "sessionId": "sess_xxx",
  "application": { "id": "com.luxera.gomoku", "version": "1.0.0" },
  "participant": { "id": "participant_xxx", "principalType": "HUMAN",
                   "principalId": "u_xxx", "role": "OWNER" }
}
```

响应里**没有** `ownerPrincipalId`。那曾经是一个平铺字段，但它问的是一个错的问题 ——
一局里可以有好几个人，"主人"只是 `role === 'OWNER'` 的那一个参与者。把主人当成会话的属性，
等于把"一个会话属于一个人"这个旧假设又写回类型里。

### 可用性：十态是事实，五态是投影（§4.1）

生命周期保留 R8 的**十个**状态（`DRAFT` … `DEPRECATED`），它们是对开发者后台说的；
使用者问的永远只有三个问题，于是有一层投影把它们压成**五个** `Availability`：

| Availability | 十态来源 | 在市场上 | 允许新会话 | 允许已有会话 |
|---|---|---|---|---|
| `DRAFT` | DRAFT, DEVELOPING, TESTING, REJECTED | ✗ | ✗ | ✗ |
| `REVIEWING` | SUBMITTED, REVIEWING, APPROVED | ✗ | ✗ | ✗ |
| `PUBLISHED` | PUBLISHED | ✓ | ✓ | ✓ |
| `SUSPENDED` | SUSPENDED | ✗ | ✗ | ✓ |
| `DEPRECATED` | DEPRECATED | ✗ | ✗ | ✓ |

`ApplicationCatalogue.isDiscoverable(id)` 现在**就是** `availabilityOf(id).inMarket()` —— 一个真相源。
应用不可用时开会话拿到的是 **409 `STATE_CONFLICT`（`APPLICATION_NOT_AVAILABLE`）而不是 404**：
"被下架了"和"没有这个应用"是两件事，而前者需要一个能说出口的答案。
`AvailabilityProjectionTest` 把这张表**逐行**钉住（不是断言"PUBLISHED 是 true、其他是 false" ——
那种写法对 `SUSPENDED` 和 `DRAFT` 说了同一句话，而这两行的区别正是这张表唯一有价值的地方）。

### 应用界面：`ui` 段与五种 Surface（§17/§18/§67–§69）

manifest 的第 8 个小节 `ui` 只声明三件事：

```json
"ui": {
  "type": "EMBEDDED",
  "entry": "/applications/{applicationId}",
  "minClientVersion": "1.0.0",
  "surfaces": [ { "type": "FULL_PAGE", "entry": "/applications/{applicationId}/sessions/{sessionId}" } ]
}
```

```
ui.type   EMBEDDED（平台自己实现） | REMOTE（第三方 Web，iframe） | NATIVE（移动/桌面端）
surfaces[].type   FULL_PAGE | EMBEDDED | MODAL | PANEL | INLINE     ← §67
entry 是模板，变量只有 {applicationId} 与 {sessionId}；客户端做且只做替换
```

**平台不定义 UI 渲染协议**（§69）：没有 button/color/layout/font/component。LAP 是
Application Runtime Protocol，不是 UI Rendering Protocol —— 否则最终会重新造一个 Flutter。
这条边界在代码里是机械的：`ManifestParser` 拒绝 `ui` 与 `surfaces` 里任何不认识的键
（`UI_UNSUPPORTED_KEY`），而不是静默忽略 —— 静默忽略会让平台一点一点长出 UI 参数，
而每一步看起来都只是"多支持一个字段"。

前端对应一侧是一个 `SurfaceHost` + 一张内置应用登记表（`frontend/src/surfaces/`）：
**呈现方式归平台，界面内容归应用**。五种 Surface 是五种**交互契约**（谁有遮罩、谁能被关掉、
谁会被拒绝渲染），不是五个 CSS 类；`SurfaceHost.test.tsx` 断言的是这些行为差别，
且它读的是**后端仓库里那份真的 manifest**，不是手抄的副本。

动作请求是刻意做小的：`{"action","target","input"}` + `Idempotency-Key` 头；
响应是 `{"status","result","resource","events","error"}`（顶层 `status`/`error` 是对设计稿的修正 ——
`{"result","resource","events"}` 这个形状里 `DENIED` 和"成功但无事可报"分不开）。

> **发现链就是"不要把 50000 个 action 塞给 LLM"的全部实现。** 每一级都比上一级窄一个数量级：
> 能力有几十个、某能力下的应用有个位数、某应用的动作有个位数。Agent 永远只看到当前这一步该看的那一层，
> 而"怎么做"（策略文本）是应用作者写在 `actions[].agentHint` 里的 —— 不在 DH 里。

### Agent 的 LLM 契约：能力 → 应用 → 动作（R7 起）

`AgentApplicationFlow` 有两个方向，契约不同：

| 方向 | 触发 | LLM 任务 | 它能看到的东西 |
|---|---|---|---|
| 反应 | 应用发来一条 `APPLICATION_EVENT` 且 `agentTrigger=true` | `application-action-selection` | 该资源**此刻**的 pending 动作（每条带 `agentHint` + `inputSchema`）与资源自身的 `agentHint` |
| 主动 | 用户说了一句话 → `route()` | `application-capability-selection` →（候选多于一个时）`application-selection` | 平台的**能力目录**（几十行）；选完能力才收窄到候选应用 |

**反应方向刻意不做能力选择** —— 事件已经点名了 resource，再问一遍"这该用哪个应用"是多余的，
每次都多花一次 LLM 调用，答案还永远是"就是它"。能力选择是**用户说了一句话**时的前门。

**模型可以答错，但不能答不存在的东西。** 四个幻觉出口一律拦下：能力不在目录里、应用不在候选里、
动作不在候选里、含糊不点名（候选不止一个却没说选哪个）。最后一条最要紧 ——
替模型补一个就是启发式，而"绝不降级到启发式"是明确要求的性质。反过来，候选**只有一个**时不问
第二次 LLM：一个候选的"选择"只是在花钱让模型复述输入。

**门槛**：`capability != null && confidence >= app.lap.capability-threshold`（默认 0.6）。
这个阈值就是设计稿那句「大多数日常聊天不需要任何应用」的可测试版本 —— 做成配置项，因为调低会让
闲聊变成应用调用，调高会让明确的请求被漏掉。能力目录的指纹（按 id 排序后 SHA-256）进 metadata，
事后翻 `llm_calls` 能知道模型当时看的是哪一版目录。

**「具体打法」归应用，通用行为准则归数字人。** 「会赢就赢、不要解释算法」在 `AgentApplicationFlow`
的提示词里（对任何应用都成立）；「能三连就三连、否则阻断对手」在 `tictactoe` 的
`actions[game.make_move].agentHint` 里。这条分界由 `DhApplicationKnowledgeArchitectureTest` 钉住：
DH 的源码里不许出现任何一个具体应用的名字、动作 id 或状态字段。

`scripts/check-lap.sh` 是这一面唯一的端到端守卫：`check.sh` 覆盖的是聊天/数字人链路，
对应用平台**零覆盖** —— 这就是为什么"测试全绿"在这里什么也保护不了。

### 数字人怎么参与一场会话（R13 起）

删掉 installation 之后，"这个数字人能不能动手"从**应用级**变成了**会话级**的问题：v1 问的是
"它装了游戏没有"，v2 问的是"它在这一局里吗"。一个数字人可以在 A 局里坐着、在 B 局里完全不存在 ——
v1 的 `installation` 表达不了这件事。R13 补上的就是这半边。

**反应路径是一个八段流水线**（`AgentApplicationFlow`，R13 从"一个方法"拆成八个阶段）：

| 段 | 它回答的问题 | 失败会怎样 |
|---|---|---|
| `READ` | 这个资源是什么？ | 停（`RESOURCE_NOT_FOUND`） |
| `LOCATE` | 这件事发生在哪一场会话里？ | **不停** —— 见下 |
| `CONTEXT` | 把这场的 sessionId 钉进 `InvocationContext` | 停不了 |
| `PENDING` | 现在有什么可做？ | 停（`NOTHING_PENDING`） |
| `ELIGIBLE` | LLM 可用吗？ | 停（`LLM_UNAVAILABLE`，**绝不降级到启发式**） |
| `DECIDE` | 做哪一个？入参是什么？ | 停（`NO_DECISION` / `NO_RESPONSE`） |
| `EXECUTE` | 平台收下了吗？ | 停（`REFUSED:<状态码>`，且**不算做过**） |
| `RECORD` | 记进现实账本 | —— |

`PipelineReport(stoppedAt, reason, sessionId, strategy, actionId)` 是这条流水线说出来的那句话，
它存在的理由是"数字人没反应"有很多种，而它们该被分开：资源不存在、无事可做、LLM 不可用、
模型没点名、平台拒绝 —— 每一种都对应一个不同的修法。

**`LOCATE` 刻意没有返回值。** 定位会话是**尽力而为的富化，不是闸门**。把它做成闸门会有一个
当时看不出来的后果：`reminder://owner/{userId}` 的资源行上从来没有 `sessionId`（R5 起一直是 NULL），
那些应用的事件会从此一个人也唤不醒 —— 而所有既有断言仍然全绿。

**定位有四条策略，查询与承诺分成两个方法**（`SessionResolver`）：

```
显式 context.sessionId  ─┐
资源行上的 sessionId     ─┼─→ locate(): 只读, 一次都不写
平台说"我在这一场里"     ─┘
                          ─→ enter(): 上面三条 + 可发现的开着门的场 + 都没有就开一场
```

`locate` 跑在**每一条**事件上，它一次都不许 join；`enter` 才谈得上进去。而 `enter` 遇到
"点名了一场进不去的"（`INVITE_ONLY` 且没被邀请 / 满员 / 平台看不见）会**抛**
（`NEEDS_INVITATION` / `SESSION_NOT_VISIBLE`）而不是退回 `ensureSession`：否则一次失败会变成一次
静默的复制 —— 邀请你的人在那场里等着，而你在新的一场里对着空房间。

**数字人进场只有一条路：被邀请。**

```
POST /api/v1/sessions/{id}/invitations            （只有会话主人）
   {"targetType":"AGENT","targetId":"<companionId>"}
        │  铸票: 明文只在响应里出现一次, 库里只有 SHA-256
        ↓
InvitationService.invitationEvent()  →  APPLICATION_INVITATION（平台事件）
        │  LapEventPublisher.publishPlatform() —— 不过 manifest 的 triggersAgent 闸门,
        │  也不过 AgentRouteResolver 的名单（收件人此刻还不在这一场里）
        ↓
DhApplicationEventSink（单向门）  →  EventProcessingChain  →  数字人的邮箱线程
        ↓
AgentApplicationInvitationHandler.decide()   ← 问 LLM, 不猜
        ├─ ACCEPT → port.joinByInvitation(token)   ← 与真人点开 /join/{token} 逐字相同的门
        │            失败才试 port.joinSession(sessionId)（票死了但门还开着）
        │            两扇都没开 → 什么都不记（不改口说"谢绝"）
        ├─ REJECT → 记 APPLICATION_INVITATION_DECLINED
        └─ IGNORE → 什么都不做, 也什么都不记（LLM 不可用 / mock / 答得不能采信）
```

**明文 token 在这条链上只有一个去处**：`joinByInvitation` 的第一个参数。不写日志、不写账本、
不进提示词、不进异常消息 —— 一条凭据一旦被记进"记忆"里就不再是凭据了，而账本是 append-only
且会被回放、被投影、被读进提示词。

**`EXTERNAL_AGENT`**（R9 加进 `PrincipalType`）是"外部 Agent"的位置：REST / MCP / 内部
`ApplicationRuntimePort` 三条入口上的 Agent 都是 `Participant`，区别只在谁替它验身份。

### MCP 适配器（`POST /mcp`，R6 起）

MCP 是**适配器，不是第二个平台**。它只做两件翻译，两件都通向已经存在的那条路：

| MCP 方法 | 通向哪里 |
|---|---|
| `initialize` / `notifications/*` / `ping` | 什么都不通 —— 纯协议状态，不碰数据库 |
| `tools/list` | 动作发现（能力 → 应用 → 动作这条链，可由 `capabilityId` / `applicationId` 收窄） |
| `tools/call` | `ActionGateway.execute()` —— 与真人 `POST /api/v1/actions:execute` **同一个**入口 |

协议版本 `2025-06-18`，JSON-RPC 2.0。`DELETE /mcp` 关掉一个协议会话。

> **MCP Session ≠ ApplicationSession。** MCP 的协议状态（协商版本、客户端信息）只活在适配器
> 内存里（`McpSessions`），`application_session` 一行都不会因为它而增减 —— 归属链是平台的概念，
> 不是某个传输的概念。这条不变量两边都有测试钉住：进程内的 `McpProtocolTest` 断言整条往返前后
> `application_session` 计数不变，`check-lap.sh` 断言 14 在真实服务上再断言一遍。
> 少了后半句，"没有创建会话"也可能只是因为那条链路根本没执行 —— 所以它同时断言棋盘真的变了。

**工具名**是 `<应用短名>.<动作 id 里的点换成下划线>`（`tictactoe.game_make_move`），短名 = id 的
最后一段。井字棋与五子棋的**动作 id 完全一样**，靠应用短名分开；若两个应用的短名还撞车，
整个目录一起退化成全名（`com_luxera_a_b.game_make_move`）—— 只给其中一个改的话，工具名会变成
"取决于另一个应用存不存在"的东西。**注意：短名是 id 的最后一段，不是"同类应用的统称"** ——
远端五子棋 `com.luxera.remote-gomoku` 与内置 `com.luxera.gomoku` 的短名各是 `remote-gomoku` 与
`gomoku`，census 各为 1，**不撞 → 用短名**；全名只在短名真的撞车时才出现（R15 的 E2E 第一版
想当然写了全名而吃 `TOOL_NOT_FOUND` —— "两个五子棋并存"是 §107 的证据，但并存 ≠ 撞车）。
描述文字（`description`）是应用的 `agentHint` 与资源模板的投影，LLM 对应用的了解**只有**这一段。

> **`/mcp` 在 `SecurityConfig` 里是 `permitAll`，这不是漏洞。** MCP 客户端没有 JWT —— 它是外部
> Agent，手里只有 `X-Mcp-Principal` + `X-Mcp-Service-Key`，由 `McpPrincipalResolver` 自己验。
> JWT 那一层**表达不了** MCP 的身份，所以留在 `anyRequest().authenticated()` 后面的结果是每个 MCP
> 请求都在过滤器上变成 Spring 默认的 403 错误体,连 `initialize` 都到不了控制器。
> 真正的门在控制器第一步：服务密钥（`app.lap.mcp.service-key`，生产上由 `LAP_MCP_SERVICE_KEY` 给）
> **留空即 MCP 完全关闭**，每个请求都被拒。
>
> 这个坑是 `check-lap.sh` 断言 14 抓到的 —— 进程内测试抓不到它：`application-platform` 的测试应用
> 没有 `SecurityConfig`（它在 `platform-kernel`），过滤器链压根不在场。补的守卫是
> `bootstrap-app` 的 `McpEndpointSecurityTest`（唯一同时看得见 `SecurityConfig` 与 `McpController`
> 的地方），断言"带正确密钥的握手必须成功"且"无凭据时回的是 JSON-RPC 信封而不是 Spring 错误体"。

> **已知产品缺口（不是测试的将就）**：AGENT 身份目前**没有** HTTP 安装入口 —— `/install` 从 JWT 解析
> principal（那是真人的路），而 MCP 面明确拒绝 `HUMAN` 声明。所以 `check-lap.sh` 断言 14 里的
> AGENT 安装行是用 SQL 造的，`McpProtocolTest` 也直接调 `InstallationService`。将来要支持
> "第三方 MCP Agent 自己装应用"，得有一条属于 AGENT 的安装路径。

数据面另走 **DHCP v1**（`contracts.dhcp`：`DhcpFrame` + 11 种帧类型）经 `/ws/simulator`。
聊天平台在这条链路上只看见一台"机器用户设备"（`simulator_devices` 表），完全不知道 Companion 的存在。

`contracts.provision` 里的 `CompanionProvisioningPort` / `ChatProvisioningPort` 是伴侣创建时的
跨平台编排端口（DH 拥有 companion，chat 拥有 user account + simulator device）。

### 生命周期、REMOTE 与投递（R8 起）

**状态机**在 `ApplicationStatus` 上，不在服务里 —— 合法迁移表是领域知识，放枚举上就只有一个地方
能回答"能不能"。`ApplicationLifecycleService` 只做三件事：查当前状态、问规则、把结果落下去。
`PATCH /api/v1/applications/{id}/status` 是唯一入口，**只有 `SYSTEM` 与 `APPLICATION` 身份推得动**
（真人拿 JWT 能到达这个端点，但会被 403 挡下）。

```
DRAFT → DEVELOPING → TESTING → SUBMITTED → REVIEWING ─┬─→ REJECTED → DEVELOPING
                                                      └─→ APPROVED → PUBLISHED ─┬─→ SUSPENDED ⇄ PUBLISHED
                                                                                └─→ DEPRECATED（终态）
```

> **状态字段必须真的被读，否则它与不存在没有区别。** 这里有两处实际后果：`transition()` 把
> `application.status` 与 `application_version.status` **一起**推进（后者正是 `VERSION_IMMUTABLE`
> 的依据），而发现面按 `isDiscoverable()` 过滤 —— 一个被挂起的应用从能力/应用/动作三个列表上
> **一起消失**，而不是只在一个没人读的字段上写着 `SUSPENDED`。
>
> **`SUSPENDED` 是软停用**：从发现链上撤下，**已安装的调用不受影响**。一盘正在下的棋不该因为
> 运营点了"暂停"而突然走不动。`DEPRECATED` 是终态，`canMoveTo` 对任何目标都返回 false。
>
> 应用与版本行的状态必须一起动，否则会出现"应用已停用、版本仍在架上"这种谁也不知道该信哪一份的
> 状态。`LifecycleStateMachineTest` 里有一条 `theVersionRowsMoveWithTheApplication` 就是钉这个的 ——
> 它在实现里抓出过一个真 bug：恢复分支的条件写成了"当前是 PUBLISHED"，而挂起那一步刚把它改成
> SUSPENDED，于是那条分支是**谁也没走到过的死代码**，症状是"应用恢复了、版本还在架下"。

**REMOTE 应用**是「加一种运行时 = 加一个 handler」这句话在远端上的兑现。内置与远端在宿主侧唯一的
实质差别是：后者的 handler 由**平台**提供，应用作者不需要提交任何代码，只需要一个能收 HTTP 的地址。
`RemoteApplicationRegistrar` 为 manifest 里的**每一个** action 各挂一个转发 handler（而不是挂一个
"远端应用"再靠动作名分派），于是"每个动作都要有 handler"那条发布前校验自动成立。

| 关切 | 做法 |
|---|---|
| 身份 | 头 `X-Lap-Application` / `Version` / `Action` / `Principal` / `Timestamp` + body 里带 `principal` 与 `correlationId` |
| 完整性 | `X-Lap-Signature: sha256=…` —— HMAC-SHA256 over `timestamp + "." + body`。**时间戳纳入签名**，一次被截获的请求才不能在没有密钥的情况下被无限期重放 |
| 密钥 | manifest 里写的是 `runtime.remote.authRef`，那是**名字**不是密钥；名字由平台配置解析（`app.lap.remote.auth.<name>`），**manifest 里永不出现密钥** |
| 幂等 | 转发的是**派生**键（`sha256(applicationId@version@action@principal@resource@input)`），不是调用方那把。调用方的键只在 `(principal, key)` 作用域里唯一，两个人各用 `"1"` 会在远端撞成同一次调用；派生键是"这一次逻辑调用"的确定函数，所以重试仍幂等、跨调用方必不同 |
| 失败 | 硬超时 → `REMOTE_TIMEOUT`；连不上 → `REMOTE_UNAVAILABLE`；HTTP 409/404/403/401/400/422/500 映射到与 REST **同一套** `ActionStatus`；`authRef` 解析不到是**平台没部署好**（`REMOTE_AUTH_UNRESOLVED`，FAILED 而非 DENIED —— 报 403 会让人去查权限，查半天发现是配置漏了） |
| 配置 | `app.lap.remote-applications` **默认为空** —— 一个默认指向某台服务器的地址，会让机器在启动时才暴露出来 |
| 状态投影 | 写动作成功且远端返回 `state` 时，`RemoteActionHandler` 把它写进平台的 resource 行（R14 起）—— 见下节 |

**远端状态的投影（R14）**回答的是 E2E 第一次跑就暴露的缺口：内置 handler 自己 `ctx.write(state)`，
远端应用拿不到平台的写句柄，于是"远端下完了棋"与"平台读得到那盘棋"之间裂开 —— 真人 A 落子、
真人 B `GET /api/v1/resources` 404。投影的三条规则，每一条都对着一个会写错的方向：

```
远端(真身)                    平台(resource 行)
   │ make_move 成功, 返回 state ──► RemoteActionHandler 投影一次
   │ 读动作(game.state) 成功    ──× 不投影: 读不该让资源版本 +1
   │ 投影写失败                ──× 不回滚调用: 远端已改完, 回 409 等于说谎
```

远端仍是唯一真相（非法落子只有它判得出），平台这一行是它最新一次写入的快照 —— manifest 里
这个资源因此声明 `RESOURCE_STORE` 而不是 `APP_OWNED`：后者的语义是"有一个 Java 投影器去读应用的
真表"，而远端应用在平台进程之外没有 Java 投影器。

**Developer API（R14）**补上"一个第三方要上架，得先有东西可推"的前一半：`developer` 表
（`ownerUserId` 指向真人，一个真人可持多个开发者身份），`POST /api/v1/developers` 幂等，
`POST /developers/{id}/applications` 的语义是**认领** —— 应用 id 就是 manifest 的反向域名身份，
第二个认领者 409 `APPLICATION_TAKEN`；归属闸门是 `requireOwned`（`NOT_YOUR_APPLICATION`，
平台自持 `null` actor 逃生口）；挂起开发者是**吊销钥匙**（不能再认领新的），已认领的应用一行
不动。三个端点在 `SecurityConfig` 里逐条放行并写明理由 —— 它们的身份是服务密钥，JWT 这一层
表达不了（过滤器层的 403 是控制器与单测都看不见的故障面，R14 的 E2E 抓出过一次）。

**双 SDK（R14）**把协议的第一里交给应用作者：Python `sdk/python/luxera_application`
（零依赖纯标准库 —— 第三方生态的第一里不该先问人要 pip）与 TS `sdk/typescript`
（node:crypto）。两侧与 Java 平台共用**同一套 HMAC**（`sha256=` + hex(timestamp + "." + body)，
300 秒重放窗，常数时间比较），Python 侧的 `IdempotencyStore` **连失败也缓存** —— 平台转发的
是派生幂等键，重试时该拿到同一个答案（哪怕是失败答案），而不是第二次执行。参考实现
`remote-apps/gomoku` 用纯标准库实现了动作/错误码与内置五子棋同名同义的远端，
`LAP_SERVICE_SECRET` 缺失时它 503 `REMOTE_NOT_CONFIGURED` 而不是裸跑。

**投递**分两种模式，同一个 `SubscriptionService` 发出去：`SINK` 立即调 `ApplicationEventSink`
（`afterCommit`，无事务时立即投递），`INBOX` 落 `lap_outbox` 由 `OutboxRelay` 异步投递。
outbox 的主键是 `sha256(eventId + "@" + subscriptionId)` —— **该事件的确定函数**，所以
"同一事件的重复入队"是同一次投递而不是第二次；重放安全由此成立，不需要消费者那边再取一次幂等。
`OutboxRelay` **刻意不带 `@Transactional`**：每行自己的 `save` 就是一次事务，投递成功与状态回写
不会因为隔壁行失败而一起回滚 —— 语义是**至少一次**，代价（重复投递）由上面那把确定主键兜住。

> **投递失败绝不静默丢弃**：`attempts` 累加 + `last_error` 落库，超过 `app.lap.outbox.max-attempts`
> 转 `DEAD` 并停止重试。留一行能查的死信，好过让它消失。

### 生态整链验收（R15）

`scripts/check-ecosystem.sh` 把 §126 那条完整链一条脚本走完 —— 它自己起远端五子棋（Python）
与带 `LAP_REMOTE_APPLICATIONS` + `LAP_MCP_SERVICE_KEY` 的 jar，然后依次验七组断言：

| 断言 | 链上的那一跳 | 判据 |
|---|---|---|
| E1 | 发现链 | 内置与远端两个五子棋在 `game.play` 候选里并存（§107） |
| E2 | Human A 开局 | `POST /applications/{id}/sessions` 200，`game.create` 200，落子天元 |
| E3 | 分享链接的票 | 铸票 200 → `/join/{token}` 200（幂等不烧名额；第二个账号由 `CHECK_USER_B`/`CHECK_PASS_B` 升级成完整三人局） |
| E4 | Agent 进场落子 | 定向邀请（`targetType=AGENT`）200 → participant 行 + 授权行 → MCP `remote-gomoku.game_make_move` 200 且 `isError=false` |
| E5 | 共享世界 | 同一行 resource 上 `board[112]=X`(Human) / `board[7]=O`(Agent)、手数=2、参与者行数与兑票幂等一致、225 格整盘读回 |
| E6 | 接口面 | `GET /capabilities` 有 `game.play`、`GET /actions` 有 `game.make_move`、`reminder.manage` 候选里没有游戏（§110 能力隔离） |
| E7 | 审计链 | `application_action_log` 按 `resource_uri` 查，HUMAN 与 AGENT 各有行动 |

它抓出过一个真缺陷：`ParticipantService.join` 对**已在场者**也拿邀请的 role 覆盖原 role ——
场主兑自己的分享链接（幂等、行数不变）就被降成 MEMBER，随后的定向邀请回 `NOT_SESSION_OWNER`。
既有幂等单测只数行数。修法是已在场者跳过角色重置，新单测钉住。这条链上每一步都有
别的断言守着（§115–§118 的架构规则、`check-lap.sh` 断言 11/14、各单元测试），
E2E 验的是**它们拼成一条链之后仍然成立**。

**空闲会话回收**（`SessionReaperJob`，默认阈值 168 小时）**只结束、从不删除**。这条区别不是措辞上的：
被结束的会话仍然解释得通（还记得是谁、装的哪一版、在哪个安装下开的），而删掉的会话会让它名下所有
`action_invocation` 变成查不到上下文的孤儿。阈值与 `ActionInvocationReaperJob` 差着三个数量级
（7 天 vs 60 秒），因为两件事问的是不同的问题："这个人还在玩吗"与"这次调用还活着吗"。

---

## 4. 测试怎么在"没有另一个平台"的情况下跑

这是解耦是否彻底的**试金石**：`digital-human-platform` 的 309 个测试在 classpath 上
**既没有 chat-platform、也没有 application-platform** 的情况下全部跑通。

- `digital-human-platform/src/test/java/com/luxera/companion/DigitalHumanTestApplication.java`
  —— 测试专用启动类，`@SpringBootApplication` + 三个假端口 Bean。
- `.../InMemoryChatWorld.java` —— 完整的内存版 `ChatWorldPort`（会话/消息/幂等索引），
  让"她写下的回复"能被读回来。`publishEvent` / `recordBoundary` / `touchThread` 是刻意的空实现，
  代码里有注释说明为什么（这些语义属于聊天平台，跨平台行为在 `bootstrap-app` 里对真实适配器测）。
- `.../InMemoryGameApplication.java` —— 一个**真能玩的小应用**（3×3 棋盘、`game.create` /
  `game.state` / `game.make_move`），实现 `ApplicationRuntimePort` 且只用 contracts 的类型。
  刻意**不做**成返回 `Optional.empty()` 的 mock：空 mock 会让 `AgentApplicationFlow` 悄悄腐烂
  而测试全绿 —— 参考应用在 DH 侧必须真的可下，这条测试才有意义。
- `.../architecture/DhApplicationKnowledgeArchitectureTest.java` —— 反过来钉住"DH 里**不许**有
  具体应用的知识"（R7 加）。它刻意**不是** ArchUnit：要禁的是字符串与变量名（`game.make_move`、
  `board`、`井字棋`），而 ArchUnit 看的是依赖与类名，两者都看不见 —— 那样写会得到一条
  **通过但什么都没检查**的规则。提醒应用的身份与词汇只允许出现在 `tool/ReminderService.java`
  （DH 侧通往应用的那唯一一道门），并额外断言白名单**不是空壳**。

跨平台的端到端行为在 `bootstrap-app` 的测试里验证 —— 那里三方都在：`LapEndToEndTest`
走的是「真人落子 → 应用发事件 → DH 翻译并路由 → Agent 决策 → 经端口落子」这条完整链路，
链路上没有一处认识井字棋。任何一侧单独在场，都凑不出这条往返。

---

## 5. 构建与部署

### 构建

```bash
cd backend
mvn clean test                       # 全模块 750 测试
mvn -DskipTests package              # 产出可执行 jar
```

> `mvn -pl chat-platform` 只从**本地仓库**取同级模块的 jar，**必须带 `-am`**
> （`mvn -pl chat-platform -am ...`）。
>
> 踩过的坑：本地仓库里那份 `companion-platform-contracts` 是旧的（新增的 `contracts.application.*`
> 与 `contracts.spi.ApplicationEventSink` 不在里面）时，`-pl application-platform` 编译照样能过
> （class 文件是上次 reactor 构建留下的），但 surefire 在挑选测试类时会**静默吞掉**
> `NoClassDefFoundError`，于是签名里碰到新类型的测试类一个都不跑，只报剩下的那几个 —— 看起来全绿，
> 实际少跑了一半。**判断依据是每个模块的 `Tests run` 总数，不是 BUILD SUCCESS。**
> 修法是 `mvn -o install -DskipTests` 把本地仓库刷新一遍。

### 产物

```
backend/bootstrap-app/target/companion-platform-bootstrap-1.0.0.jar   ← 唯一可执行 jar（约 52 MB）
```

### 启动

```bash
cd backend && ./run.sh                 # 打包 + 起在 8081
# 等价于:
java -jar backend/bootstrap-app/target/companion-platform-bootstrap-1.0.0.jar
```

### systemd（生产）

`scripts/deploy.sh` 安装的单元使用上述 jar，工作目录 `backend/`，
**必须带 `EnvironmentFile=/etc/companion/.env`**（`DEEPSEEK_API_KEY`；缺了会静默降级成 Mock LLM，踩过）。

### 当前拓扑 vs 分进程拓扑

当前是**单进程**：一个 Spring context 同时装三个平台，`bootstrap-app` 组装。
三个平台的代码边界已经切干净，改成两个（或三个）进程不需要动 `chat-platform` /
`digital-human-platform` / `application-platform` 一行代码，只需：

1. 各加一个 launcher 模块（各自的 `@SpringBootApplication` + profile yml + repackage）；
2. 让 SPI 端口走 HTTP/WS 而不是本地 Bean（当前六个端口都已有本地适配器实现，远程适配器尚未写）；
3. `app.simulator.chat-ws-url` 指向 chat 进程的 `/ws/simulator`（当前默认
   `ws://127.0.0.1:8081/ws/simulator`）；数据面若要走 WS 而非进程内直调，
   另开 `app.simulator.backend=websocket`（默认 `inprocess`，由 `ChatSimulatorClient` 的
   `@ConditionalOnProperty` 控制）；
4. nginx：`/api/chat/*` 与 `/ws/simulator` → chat 进程；`/api/v10/*` → DH 进程；
   SSE 需要 `proxy_buffering off` + `proxy_http_version 1.1`。

单进程是当前的**部署选择**，不是架构约束。

---

## 6. 数据库

同一个 PG 实例，同一个 `companion` 库。单进程下三个平台共用一个 DataSource，
**表不重叠、跨模块无 FK** —— 这条靠的是"各平台的实体只映射自己的表"这一约定，
加新实体时请确认表名没有和另一个平台撞车（`ddl-auto=update` 不会替你报错）。
跨平台状态同步只有三种合法通道：

1. DHCP v1 WebSocket 协议（实时主路径）；
2. 共享 `outbox_event` 表（生产者 = chat，消费者 = DH 的 `OutboxRelayJob`；可靠兜底）；
3. `event_log` SSE 表（前端唯一事件源）。

应用平台的表（R4 起全部生效；`dh_` 前缀的是过渡期遗留，R8 由 `scripts/lap-drop-legacy.sh` 删除）：

| 表 | 说明 |
|---|---|
| `developer` | 应用作者；R8 的 Developer API 挂在它上面 |
| `application` | 稳定 id（如 `com.luxera.tictactoe`）+ 10 态生命周期状态 |
| `application_version` | **manifest 挂在版本上**（不是挂在 application 上），`UQ(application_id, version)`，发布后不可变 |
| `capability` / `application_capability` | 能力目录（`game.play` / `reminder.manage`）与版本-能力绑定，从 `capabilities.json` 播种 |
| `installation` | Principal × Application，`UQ(application_id, principal_type, principal_id)`；安装**钉住版本** |
| `permission_grant` | 安装之下的第二维：能力级或动作级授权 + 风险上限 + 过期 |
| `application_session` | 平台级会话；应用自己的业务对象挂在它下面 |
| `resource` | **统一读模型**：`uri` 唯一 + `state_json` + `state_version`（CAS 用） |
| `subscription` | 会话之内的事件订阅：`SINK` 立即投递，`INBOX` 落 `lap_outbox` 由 `OutboxRelay` 异步投递 |
| `action_invocation` | 幂等账本，`UQ(principal_type, principal_id, idempotency_key)` + `request_hash` + `started_at` |
| `application_action_log` | 审计（权限判定 + 执行结果分开记 —— 旧表把两者塞进同一个字段，是废字段） |
| `reminder_item` | **应用自有**（`backing: APP_OWNED`）：提醒的真相在这里，读的时候由 `ReminderResourceProjector` 投影成 `reminder://owner/{ownerId}` 的 `ResourceView`。字段名与旧表不同（`note` / `dueAt`），但 DH 的 REST 面把它们翻译回 `content` / `remindAt` |
| `lap_outbox` | `INBOX` 订阅的待投递事件（主键 = `sha256(eventId + "@" + subscriptionId)`，**该事件的确定函数** —— 于是重放不会投第二次）；`status ∈ PENDING/DELIVERED/DEAD` + `attempts` + `last_error` |
| `dh_application` / `dh_game_session` / `dh_application_action_log` | **已删除**（R8 由 `scripts/lap-drop-legacy.sh` DROP）。`/api/v10` 应用面自 R4 起已删（实测 404），这三张表从那时起就没有写入方 |
| `reminders` | **已删除**（R8 一并 DROP）。R5 起就没有写入方了 —— 提醒（含生日提醒）的创建/完成/取消全部经 `reminder.create` / `reminder.complete` / `reminder.cancel` 落到 `reminder_item`；DH 侧只剩 `ReminderService` 通过 `ApplicationRuntimePort` 的读与调用。`check-lap.sh` 断言 1 现在会**逐张断言这四张表不存在** |

> **棋局状态最终不建表**：棋盘**就是**一条 Resource（`game://session/{id}` 的 `state_json`），
> 在 action 的同事务内经 `ResourceStore` 写入。这是「Resource 是统一读模型」最直接的证明。

需要 `vector` 扩展（`memory` 实体有 `vector` 列）：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

---

## 7. 加新代码之前

- **放哪个模块？** 看它是否依赖另一个平台。依赖了 → 说明边界破了，先想清楚是不是该走 SPI 端口。
- **新包放哪？** 加进任一模块都会自动被 `check-v10.sh` 纳入包归属检查；若与已有模块重名会立刻报错。
- **需要在 DH 里读会话/发消息？** 注入 `ChatWorldPort`，别去 import chat 的类（编译期也 import 不到）。
- **需要在 DH 里操作应用？** 注入 `ApplicationRuntimePort`，别去 import application 的类
  （同样编译期就 import 不到）。要读应用状态就用 `read(uri)` 拿 `ResourceView`，
  **不要在 DH 里解析应用的 JSON** —— 一旦 DH 认识某个应用的字段名，"加第二个应用不改 DH"
  这句话就不成立了。
- **改完跑什么？** `mvn clean test`（看每个模块的 `Tests run` 总数，不是只看 BUILD SUCCESS）
  + `bash scripts/check-v10.sh` + 起服务 `bash scripts/check.sh`。
