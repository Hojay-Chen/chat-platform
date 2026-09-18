# V11 — 客户端面（`/api/client/**`）：让 Agent 像真人一样用聊天软件

> 对应需求：仿真 Agent 平台 V2.2 §6.1–§6.5。
> 用户原话：
>
> > 我要的效果就是这种聊天平台提供覆盖聊天平台的全部功能的接口并且符合我们真人平时使用时
> > 调用接口的逻辑，甚至于你可以让**聊天平台前端和 agent 调用的是同一套聊天平台后端接口**
> > （但这里要解决授权问题）。
>
> 本文记录这一轮做了什么、**为什么这么做**，以及哪些事刻意没做。

---

## 1. 这一轮要解决什么

V2.1 的仿真平台把"外面有个聊天软件"这件事写成了一个**适配器**：
平台内部有一个 `ReminderService` 之类的类，直接读库拿消息。

那条路能跑，但它把两个问题一起埋掉了：

| 埋掉的问题 | 后果 |
|---|---|
| **Agent 绕过了聊天软件的规则** | 免打扰、静音、通知开关这些"用户设置"在 Agent 那一侧不存在，于是"她为什么没回消息"永远答不出来 —— 因为在她那里那条消息压根没出现过 |
| **接口不是"聊天软件的功能"，而是"平台碰巧需要的东西"** | 平台缺什么就加一个方法，于是接口的形状由**内部实现**决定，而不是由**真人怎么用聊天软件**决定。加第 20 个方法时已经没人记得前 19 个的语义了 |

所以这一轮把这件事**倒过来**：聊天平台先提供一组**覆盖它自己全部功能**的接口，
形状按"真人用微信的动作"排；然后 Agent 平台**实现一个"手机应用"接口**去消费它
（见仿真平台 V2.2 §4.3.7 与 `world/application/chat/`）。

**顺带解决了授权问题的一半**：前端与 Agent 走的**是同一组控制器**
（`ClientApiController`），所以"两边看到的世界是不是同一个"这件事不再需要靠对齐两份实现来保证 ——
它只有一份实现。

---

## 2. 十个动作

| # | 真人用微信的动作 | 接口 | 返回 | 认证 |
|---|---|---|---|---|
| 1 | 打开微信（自动登录） | `POST /api/client/login` | `ChatSession` | 用户 JWT **或** 接入钥匙 |
| 2 | 建立长连接 | `WS /api/client/stream` | `ClientStreamFrame` 流 | 令牌（握手时校验） |
| 3 | 看会话列表 | `GET /api/client/conversations` | `List<ClientConversation>` | 令牌 |
| 4 | 点开某人 | `GET /api/client/conversations/{accountId}/messages` | `MessagePage` | 令牌 |
| 5 | 上翻拉更多 | 同上，带 `cursor` | 更早的一页 | 令牌 |
| 6 | 打字发送 | `POST /api/client/conversations/{accountId}/messages` | `SendResult`（**201**） | 令牌 |
| 7 | 已读 | `POST /api/client/conversations/{accountId}/read` | — | 令牌 |
| 8 | 设免打扰 / 置顶 | `PUT /api/client/conversations/{accountId}/notification` | `ConversationNotificationSetting` | 令牌 |
| 9 | 看某人资料 | `GET /api/client/contacts/{accountId}` | `ContactProfile` | 令牌 |
| 10 | 搜索会话 | `GET /api/client/conversations?q=` | 过滤后的列表 | 令牌 |
| — | （Agent 平台铸号） | `POST /api/client/provision` | `ProvisionedChatAccount`（**201**） | **管理密钥** |

**第 4 与第 5 是同一个端点**，区别只在带不带 `cursor`；**第 10 与第 3 也是同一个**，
区别只在带不带 `q`。这是刻意的：凑成十二条会让读者以为它们是四件不同的事，
而真人在微信里做的只有"点开某人然后上翻"和"在列表上打字搜索"这两个动作。

**分页由聊天平台控制**（与微信一致）：

```java
DEFAULT_PAGE_SIZE = 20        // 不传 limit 时
MAX_PAGE_SIZE     = 100       // limit 的上限，超过就压到 100
```

**`limit` 的上限在服务端而不是在文档里**，因为它是一条**保护**而非一条约定：
一个传 `limit=100000` 的客户端要么拿到一份被截断却自称完整的列表，
要么把服务端的连接池占满。压到 100 之后，客户端拿到的 `nextCursor` 一定是有效的。

---

## 3. 通知信号：为什么它里面没有消息正文

```java
public record NotificationSignal(
        long   signalId,           // 该账号内单调递增 —— 去重与补发都靠它
        String conversationId,     // 哪一段对话
        String fromAccountId,      // 谁发的（账号 ID，不是姓名）
        LocalDateTime raisedAt) {}
```

**四个字段，一条正文都没有。** 这不是省事，是需求本身：

> 每一条消息，现实世界的表现是**每一条都根据用户在聊天软件设置的通知规则（是否免打扰）
> 来判断是否要通知到用户**……在聊天软件与 agent 平台的连接通道中发送一条表示消息通知
> **不携带消息内容**的数据。

理由在 Agent 平台那一侧看得很清楚：**一部真手机响的时候，她只知道"有人说话了"，
不知道说了什么。** 要知道，她得**做一个动作** —— 拿起手机、点亮屏幕、打开聊天软件、
点开那个人。那四步在仿真里是四个 Action，各自的代价与时机都被建模。

如果信号里带了正文，那四步就会被短路掉，而"她在忙所以没看手机"这个状态**在架构上不再存在** ——
从外面看，她变成了一台永远在线的机器。

### 3.1 绝不聚合

> 常见的微信和 QQ 都是**每条消息都通知**，而不是把多条消息聚合成一条消息来通知。

所以链路上**没有任何聚合点**：

- `ClientNotificationService.onMessageArrived` 收到的事件是**逐收件人**的
  （`MessageArrivedEvent`），循环里永远只有一个人；
- 每一条消息各产生**一条**信号，`signalId` 各 +1；
- `NotificationSignalLog` 是一份**追加日志**，不是"每个会话保留最新一条"的聚合表。

**为什么值得单独写一段**：聚合看起来是个显然的优化（"同一个群 100 条消息不该响 100 次"），
而它一旦被"顺手"加上去，产品语义就变了 —— 而它变得不容易被发现，因为聚合后的行为
（响一次）在"只发了一条消息"的测试里完全一样。

*顺带一提：那条"同一个群 100 条消息不该响 100 次"的诉求，正确的解法是用户自己的
免打扰设置，而那就该由用户来设。*

### 3.2 免打扰判定在聊天平台这一侧

> 每一条都根据用户**在聊天软件设置的通知规则（是否免打扰）**来判断是否要通知到用户。

规则只有两条：

```text
免打扰生效中  →  一条信号都不产生（未读数照旧 +1 —— 她打开聊天软件能看到红点）
免打扰未开启  →  每一条消息各产生一条信号（绝不聚合）
```

**判定必须在这里发生**，不能推给下游：信号一旦发出去，对面就知道"有人说话了"，
而"消息免打扰"的语义是**连铃都不响**，不是"响了但我不看"。

**判定是"现查"的**，不是把某个值一路传下来：

```java
if (readStates.isMutedNow(conversationId, recipientAccountId)) { return Optional.empty(); }
```

`isMutedNow` 读的就是 `conversation_read_state.muted_until` 那一行。判定的时刻必须是
**消息到达的时刻** —— 用户在消息到达前 1 毫秒打开了免打扰，那一次就该不响。
把 `muted` 作为参数传下来，会让代码里出现两个"现在几点"。

**免打扰是逐人的**：同一个群里 A 免打扰而 B 没有，这一次消息对 A 不发信号、对 B 发。
这由 `MessageArrivedEvent` 逐收件人发出保证，而不是由这里的一个循环保证。

### 3.3 只判了一半，另一半在 Agent 平台

"要不要响这一下铃"实际上是**两层**，本仓只做第一层：

| 层 | 判定者 | 位置 | 拦下之后的表现 |
|---|---|---|---|
| ① 免打扰 | 聊天软件的用户设置 | **本仓** `ClientNotificationService` | 信号都不产生 —— 对面一无所知 |
| ② 手机音量 / 静音模式 | 手机的物理设置 | 仿真平台 `Device.NotificationPolicy` | 信号产生了但没响 —— 投 `device.notification-dropped` |

**两层分开写下来的价值**：她"没听见"这件事因此是**可解释**的 ——
是"对方被免打扰了"，还是"她手机静音了"，还是"她听见了但没看"。
合成一层的话，行为分析只有一句"她没反应"。

---

## 4. 授权：两套凭据，一个主语

这是用户点名的难点："前端和 agent 调用的是同一套接口，但这里要解决授权问题"。

### 4.1 做法：不是"区分两种调用方"，而是"收敛到同一个值"

```text
   前端（真人）                        Agent（ChatApplication 的驱动程序）
        │                                        │
  Authorization: Bearer <用户JWT>          X-Api-Key: cak_...   ← 只在 login 那一次
        │                                        │
        └───────────────┬────────────────────────┘
                        ▼
                ClientPrincipalResolver
                  ├── JWT    → accountId = 令牌 subject（那个人自己的账号）
                  └── APIKEY → agentId    = chat_api_clients.agent_id（管理员写入，自报不了）
                                 accountId = 该 agent 的聊天账号（AgentChatIdentity）
                        │
                        ▼
                同一个 ClientPrincipal（只有一个字段：accountId）
```

控制器与它下游的所有业务代码**只看 `accountId`**，它们没有机会问"你是人还是 Agent"，
因为那个答案**不在它们能拿到的类型里**。

**为什么这件事值得写成一段**：把授权做对有很多种写法，其中大多数是"在每个端点记得检查一次"。
那种写法在加第 20 个端点时开始漏 —— 而漏掉的那一次，症状是"Agent 读到了别人的私信"，
一个不会自己暴露的故障。**让它不成立，比让它被检查更可靠。**

### 4.2 三层授权，互不替代

```text
①  平台授权（Platform Authorization）
       谁能访问这台服务器。JWT / API Key。
       没有凭据 → 401；令牌坏了或过期 → 401；钥匙不认识 → 401。

②  应用会话（Application Session）
       凭据解出来的那个聊天账号**就是**它之后的权限全集。
       核心原则：不能因为 Agent 是系统内部对象，就绕过聊天平台权限。

③  Agent 能力授权（Agent Capability Authorization）
       在 Agent 平台内部：这个 agent 能不能执行这个 Action。
       由 CapabilityDescriptor.required 判定 —— 不在本仓。
```

第②条的具体表现是：**Agent 拿到的不是一个更宽的身份，而是一个普通聊天账号**，
权限与一个真人登录后一模一样。

### 4.3 钥匙只用一次，之后只剩令牌

`X-Api-Key` **只在 `POST /api/client/login` 这一个端点被受理**，换出一个普通平台 JWT
（`ptype = EXTERNAL_AGENT`，subject 是它的聊天账号），之后每个请求都只认令牌。

于是"Agent 必须像真人客户端一样登录、拿 token、带 token 请求"在实现上不是一条纪律，
而是**唯一可行的那条路**。

**为什么不能图省事让钥匙在每个端点上直接用**：一把长期钥匙一旦泄露，换不掉也收不回
（它不是一个可以过期的会话）。而"每个端点都能用钥匙"还额外带来一种身份：
**既不是用户、也不是聊天账号**的那种，它的权限只能靠每个端点各自记得写多少检查 ——
即 §4.1 里刚说过的那种写法。

### 4.4 `provision` 为什么是另一种身份

`POST /api/client/provision` 接受的是**管理密钥**（`X-Admin-Key`），不是接入钥匙。

因为 Agent 的**驱动程序**不该有能力凭空铸出聊天账号 —— 铸号是 Agent 平台
（`ownerAccountId` 那一侧）的事。这条边界落在代码里：

- `ClientPrincipalResolver.resolveAdmin` **只认管理钥匙**，且只服务这一个端点；
- `ClientPrincipalResolver.resolve` **永远不返回 SYSTEM**。

第二句是关键：管理密钥解出来的是 `PrincipalType.SYSTEM`，它不代表任何聊天账号。
让它通过 `resolve` 的话，下游每一处"这个账号参与的会话"都会退化成"全部会话" ——
而它看起来还能正常工作（管理面本来就该看得到一切）。
**把这两条路分成两个方法之后，"用管理钥匙读私信"在类型上就不成立了。**

### 4.5 `SYSTEM` / `APPLICATION` 一律拒绝

`resolve` 里有一条 `requireUsableType(type)`：这两种身份都被拒。
理由同上 —— 它们不是聊天账号，而客户端面**只处理聊天账号**。

### 4.6 一个容易漏的检查：账号是否存在

```java
if (!users.existsById(accountId)) { throw ...unauthenticated("这个账号不存在或已注销"); }
```

`POST /api/client/login` 是 `permitAll` 的，而 `resolve` 也服务 WebSocket 握手 ——
**那两处都不过 `JwtAuthenticationFilter`**。少这一句的话，一个**已注销用户**的未过期令牌
仍然能读到历史会话。这类漏洞的形状是"它只在注销之后才出现"，于是永远不会在开发时被撞到。

---

## 5. 长连接（`WS /api/client/stream`）

### 5.1 帧

```text
客户端 → 服务端:  PING（心跳）    ACK（"第 N 条我收到了"）
服务端 → 客户端:  READY  NOTIFICATION  PONG  SESSION_EXPIRED  ERROR
```

`ClientStreamFrame(type, signal, reason, lastAckSignalId)` 是四种帧的**同一个形状** ——
用可空字段区分而不是继承树，因为这是要在一条 WebSocket 上读写的东西，
而继承树会让两端的解析器各自长出一个小型类型系统。

### 5.2 握手里就完成鉴权与补发

三个决定，每个都对应一类具体的 bug：

| 决定 | 不做的话会怎样 |
|---|---|
| **握手时校验令牌**，失败发 `SESSION_EXPIRED` 再关 | 直接关连接的话，客户端只会看到"连接莫名其妙断了"，于是它会**一直重连**，而每一次都会失败 |
| **先注册进 `ClientStreamRegistry`，再做补发**（两步在同一把连接的锁里） | 两头落空：广播时我们还不在表里，补发时快照已经取完 —— 而"少响一下"**是查不出来的**，因为它与"那一刻确实没人说话"长得一模一样 |
| **补发只发信号，不发正文** | `NotificationSignalLog.since` 的返回类型里**没有**正文可用 —— 这不是一条纪律，是类型上的不可能 |

**整段补发期间持有连接的锁**：不持有的话，一条恰好在这一刻到达的广播会插到补发的中间 ——
客户端会看到序号倒挂（…5, 7, 6…）。一个只记 `lastAckSignalId` 的客户端遇到 7 就会把 5 和 6
判成"已经确认过的"，于是它们永远不会被处理。

### 5.3 先记后发：用一次可去重的重复，换掉一次不可察觉的丢失

```java
NotificationSignal signal = signalLog.append(...);   // 先记
streams.notify(recipientAccountId, signal);          // 后发
```

反过来的话（先广播、后记日志），一个**刚好在这一刻重连上**的客户端会走到补发那条路上 ——
而那时日志里还没有这一条，于是它**一条都收不到**。少响一下是查不出来的，见 §5.2。

先记后发最多带来**重复**：重连补发与广播各给了一次。而重复是**可去重**的 ——
`signalId` 在同一个账号内单调递增，客户端只要丢掉 `<= lastAckSignalId` 的那些。

> **这个取舍的形状值得单独看一遍**：两个方案都错，差别只在**错成什么样**。
> 一边是"可能丢，且丢了看不出来"，另一边是"可能重，但重了能认出来"。
> 当两边都不完美时，选那个**失败可观测**的。

### 5.4 通知失败绝不往上冒

```java
} catch (Exception e) { log.warn("...通知信号产生失败...", e); }
```

它的调用点在一次消息落库**之后**，而消息已经是事实了 ——
一个"铃声发不出去"不该把一次成功的发送变成一次 500。

`@TransactionalEventListener(AFTER_COMMIT)` 的理由有两条，第二条是硬的：

1. 通知的消费者（WS、SSE）会立刻去读库看"到底发生了什么"。在事务提交前发出去，
   它们可能读到一个还没有这条消息的世界 —— 表现为"响了，但点开是空的"；
2. **回滚过的消息不该响。** 消息落库与未读 +1 在同一个事务里，而那个事务可能因为任何原因回滚。
   在一个**已经不存在的事实**上响铃，是这一条链路上最难查的一类 bug：
   用户说他听见了，而库里什么都没有。

`fallbackExecution = true` 是为了那些**没有事务**的调用点（测试、以及将来可能出现的
"消息已经在别处提交了"的路径）。少了它，那些路径上的通知会**静默地一条都不发** ——
而"静默地什么都没发生"是这个类最不该有的失败模式。

---

## 6. 会话列表里为什么没有正文预览

`ClientConversation` 只有六个字段，**没有最后一条消息的摘要**：

```java
public record ClientConversation(
        String conversationId,
        String accountId,        // 对方（群则是群账号）
        int    unreadCount,
        LocalDateTime lastActivityAt,
        boolean pinned,
        boolean muted) {}
```

因为真人打开微信时，列表上确实有一行预览 —— 但那**不是"看见"**，那是"瞥见"。
而这里少这一个字段，换来的是 Agent 侧一条清晰的界线：

> **要读一条消息的内容，她必须做一个动作。**

`GET /conversations` 是"看一眼列表"（便宜、无副作用），
`GET /conversations/{id}/messages` 是"点开那个人"（一次真正的阅读，会推进已读）。

`ContactProfile.displayName` 对 Agent 一侧**恒为空**，理由在这个仓之外（见仿真平台 V2.2 §4.3.7）：
"这个人叫什么"是**她自己的知识**，聊天平台只给账号 ID。她要在自己的关系网里
把 `acc_xxx` 变成"妈妈"，而那件事发生在她的记忆里，不在聊天平台的用户表里。

---

## 7. 错误体与状态码

```json
{ "error": "人话", "code": "MACHINE_CODE", "hint": "下一步该做什么" }
```

与 `/api/v1/chat` **同一个形状**（`ClientApiExceptionHandler`）。刻意不让它漂到
LAP 的 `ActionResponse` 上：这个面的调用方读的是聊天平台的公开文档，
那里成功与失败都该是同一套解析逻辑。

**`401` 与 `403` 的分工**：令牌缺失/无效/过期/账号已注销 → `401`；
令牌有效但这个账号不能做这件事 → `403`。
前端与 Agent 都按这个分工处理（前者去登录，后者说明"这不是权限问题"）。

**令牌无效与过期对外是同一句话**：换一个有效凭据再来。区分开来只会告诉猜令牌的人
"你这一个的形状是对的"。

**发送成功返回 `201`** 而不是 `200`：一个新的资源（消息）被创建了，而它的 id 在 `SendResult` 里。

`SendBody` **刻意没有 `kind` 字段**：消息种类（`SHORT_ACK`/`PROACTIVE`/`SYSTEM`…）
是平台的内部词表 —— 开放它等于让任何客户端伪造一条"平台通告"
（`SYSTEM` 在界面上渲染成居中的系统提示）。对外开放面之所以能接受 `messageKind`，
是因为那边只有 agent 程序、且带白名单；而这里是**覆盖面最广的那个面**（含真人浏览器），
白名单之外的那几个取值在这里没有一个是需要的。

---

## 8. 契约（`com.luxera.companion.contracts.client`）

十个类型，全部是不可变 record，发布在 `contract` 模块里 —— **仿真 Agent 平台直接依赖它们**，
不各自复制一份：

| 类型 | 用途 |
|---|---|
| `ChatSession` | login 的返回：令牌 + 我是谁 + 什么时候过期 |
| `NotificationSignal` | 那一条"有人说话了"（**四个字段，无正文**） |
| `ClientStreamFrame` | WS 上的四种帧 |
| `ClientConversation` | 列表里的一行 |
| `ClientMessage` | 一条消息（**只有点开那个人时才拿得到**） |
| `MessagePage` | 一页消息 + `nextCursor` + `hasMore` |
| `SendResult` | 发送结果 |
| `ContactProfile` | 某人的资料（`displayName` 只有真人侧有） |
| `ConversationNotificationSetting` | 免打扰 / 置顶 |
| `ProvisionedChatAccount` | 铸号结果（含配对码与有效期） |

**为什么契约要发布而不是各写一份**：两边各写一份的话，字段名一致但**语义**会分叉 ——
例如 `unreadCount` 是"未读消息数"还是"未读会话数"。这类漂移在两边各自编译通过时
完全看不出来，只在把两边并排看时才可见。

---

## 9. 测试

22 个测试，四个测试类，外加一个夹具：

| 测试 | 条数 | 覆盖什么 |
|---|---|---|
| `ClientNotificationSignalTest` | 5 | 逐条产生信号、免打扰时一条都不产生、未读照旧 +1、事务回滚不发 |
| `NotificationSignalShapeTest` | 5 | **信号的形状** —— 四个字段，且没有一个是消息正文（反射断言，防止将来有人"顺手"加一个 `content`） |
| `ClientApiHttpTest` | 7 | 十个端点的正路与 401/403 分支 |
| `ClientStreamE2eTest` | 5 | 握手 → 补发 → 广播 → ACK → 重连不重复 |
| `ClientPlatformStub` | — | 夹具（非测试类）：一个不依赖另一个平台的聊天平台替身 |

**`NotificationSignalShapeTest` 值得单说**：它断言的是一个**类型上没有的东西**。
这类测试通常会被人说成"断言了一个恒真式" —— 而它防的恰恰是最容易发生的那种改动：
某天有人为了让界面能显示预览，往信号里加一个 `content`。那时这条测试会红，
而红的原因写在它的断言消息里（"信号里出现正文 —— 这条链路的全部意义就在于它没有正文"）。

---

## 10. 手工验收

```bash
# ① 铸一个 Agent 的聊天账号（管理密钥；调用方是 Agent 平台，不是 Agent 的驱动程序）
curl -s -X POST http://localhost:8081/api/client/provision \
  -H 'X-Admin-Key: <admin>' -H 'Content-Type: application/json' \
  -d '{"requestId":"req-1","displayName":"小助","ownerAccountId":"acc_user1",
       "relationshipType":"COMPANION","agentId":"agent_1"}'

# ② 用接入钥匙登录，换出令牌
curl -s -X POST http://localhost:8081/api/client/login \
  -H 'X-Api-Key: cak_...' 
# → {"token":"...","accountId":"acc_agent1","agentId":"agent_1","expiresAt":"..."}

# ③ 看会话列表（未读数）
curl -s http://localhost:8081/api/client/conversations -H "Authorization: Bearer $T"

# ④ 点开某人（这一步才拿到正文）
curl -s "http://localhost:8081/api/client/conversations/acc_user1/messages?limit=20" \
  -H "Authorization: Bearer $T"

# ⑤ 建立长连接（wscat）
wscat -c "ws://localhost:8081/api/client/stream?token=$T&lastAckSignalId=0"
# → {"type":"READY",...}
# 另一侧发一条消息 → {"type":"NOTIFICATION","signal":{"signalId":7,...}}   ← 无正文

# ⑥ 打开免打扰，再发一条 → 长连接上**什么都不会来**
curl -s -X PUT http://localhost:8081/api/client/conversations/acc_user1/notification \
  -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"muted":true,"pinned":false}'
```

第 ⑥ 步是这一轮最该亲手验一次的行为：**它是"什么都没有发生"，而"什么都没有发生"
是没法靠看日志确认的** —— 必须看它前面的第 ⑤ 步确实是通的。

---

## 11. 刻意没做的事

| 没做 | 为什么 |
|---|---|
| **信号里带消息正文** | §3 —— 那会短路掉"拿起手机 → 点亮 → 打开软件 → 点开某人"这四步，于是"她在忙所以没看手机"在架构上不再存在 |
| **多条消息聚合成一条通知** | §3.1 —— 与产品语义相反。想少响几次，正确答案是用户自己设免打扰 |
| **会话列表带正文预览** | §6 —— 预览等于"不用点开就看见了"，而这条界线是 Agent 侧"读一条消息必须做一个动作"的前提 |
| **接入钥匙在所有端点上可用** | §4.3 —— 长期钥匙换不掉也收不回；且会造出"既不是用户也不是聊天账号"的第三种身份 |
| **`GET /contacts/{id}` 返回用户备注/分组** | §6 —— 那是**用户自己的**通讯录知识。Agent 要建立自己的关系网，而它该从对话里长出来，不该被平台喂进去 |
| **群 / 图片 / 语音消息的完整语义** | 接口形状已经留好（`ClientMessage.kind`、`conversations` 的 `accountId` 可以是群账号），但这一轮只需要把"一对一、纯文本"这条链走通走实 |
