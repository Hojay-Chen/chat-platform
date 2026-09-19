/**
 * 一条事件对聊天室与消息列表分别意味着什么。
 *
 * <h2>为什么这条判定必须从 hook 里搬出来</h2>
 *
 * 它是这一期唯一在修的 bug, 而它原来是这么写的(老 `Chat.tsx:173`):
 *
 * <pre>
 *   const convId = String(d.conversationId ?? '')
 *   if (convId &amp;&amp; convId !== activeConvIdRef.current) return   // ← 整条事件被丢弃
 *   ...
 *   } else if (event === 'companion_message') {
 *     ...
 *     refreshConversations()                                   // ← 在这个 return 之后
 * </pre>
 *
 * 于是**别的会话来了新消息时, 会话列表根本不刷新** —— 而那恰恰是 IM 最需要的那条路径:
 * 你正看着 A 的聊天记录, B 发来一条消息, 列表上 B 那一行不动、角标不亮。
 *
 * 根因不是"忘了刷新", 而是**两件事被绑在了同一个条件上**:
 *
 * - 「这条消息要不要插进我眼前这条消息流」 —— 只有当前会话才要
 * - 「会话列表要不要更新」 —— **任何**会话都要, 因为列表画的就是所有会话
 *
 * 所以这里把它拆成两个独立的答案。放在 `lib/` 是因为: 这条路是纯的、可断言的,
 * 而它住在 `useChatRoom` 里的时候**一条测试都写不了** —— 本仓前端跑在
 * `environment: 'node'` 下, 没有 jsdom, hook 渲染不起来(见 `routes.test.ts` 文件头
 * 那段同样的说明)。一个只靠注释保护的 bug 修复, 迟早会被下一次重构还原回去。
 */

/** 只有这两个事件意味着"落了一条消息" —— 也只有它们会改会话列表的排序与摘要 */
const MESSAGE_EVENTS = new Set(['message_created', 'companion_message'])

/**
 * 会话被销毁（对面的 Agent 被删了）。
 *
 * <p>它和上面两个不是一类: 那两个是"列表里某一行变了", 这个是"列表里少了一行"。
 * 但"要不要重新拉列表"的答案是同一个 —— 都是"要"。
 */
const DELETED_EVENT = 'conversation_deleted'

/** 会让会话列表需要重新拉取的事件。消息类改排序与摘要, 销毁类直接少一行。 */
const LIST_EVENTS = new Set([...MESSAGE_EVENTS, DELETED_EVENT])

/**
 * 这条事件属不属于当前打开的那段会话。
 *
 * <h2>为什么"缺 conversationId"对两类事件的答案不同</h2>
 *
 * 老规则是 `!convId || convId === open` —— 缺会话 id 一律算当前房间的, 而它的理由是
 * 真的: `companion_typing` / `message_read` / `user_message_status` 这些**瞬时提示**
 * 本来就不带会话 id, 一律丢掉的话"正在输入…"就永远不出现。
 *
 * <p>但那条例外被**消息类**事件吃掉了, 而后果不是少一条提示 —— 是**多一条消息**:
 * 一个不带 conversationId 的 `companion_message` 会插进用户当时恰好开着的那一段对话,
 * 无论它本来属于谁。因此它不可逆: 用户看到一条对方从未在这段对话里说过的话, 而界面上
 * 没有任何东西告诉他这条是串进来的。
 *
 * <p>这条不等式是**真的发生过**的(2026-09 排障): Agent 平台那处延迟回复的
 * `publishEvent` 漏了 `conversationId`(平台里另外三处都带着), 而复查循环当时每分钟
 * 给同一条消息补一次回复 —— 一个月攒下近 7000 条这种事件。于是打开**任何**一段对话,
 * 都会看到一墙几乎一样的、不属于它的气泡。修了两处: 那边补上字段(源头), 这里不再
 * 让"没带归属"的消息类事件落地(兜底)。
 *
 * <p>所以判据不是"缺字段怎么办", 而是"这条事件插错了会不会不可逆":
 * 瞬时提示插错了顶多闪一下, 消息插错了是伪造聊天记录。
 */
function belongsToRoom(event: string, convId: string, openConversationId: string | undefined): boolean {
  if (!openConversationId) return false
  if (convId) return convId === openConversationId
  // 不带会话 id: 只有瞬时提示类可以落进当前房间, 消息类一律不落
  return !MESSAGE_EVENTS.has(event)
}

export interface RoomEventEffect {
  /** 要不要作用在**当前打开的那段会话**的消息流上 */
  applyToRoom: boolean
  /** 要不要重新拉一次会话列表 */
  refreshList: boolean
  /**
   * 当前打开的那段会话**已经不存在了** —— 用户得离开这个房间。
   *
   * <p>这是"对面把 Agent 删了"的情形: 聊天平台在那段会话被销毁时发最后一条事件
   * (见 {@code ChatEventTypes.CONVERSATION_DELETED})。用户很可能就是**开着这个房间**
   * 按的删除(手机上从会话页返回设置页, 桌面上另一标签页), 所以不处理的话他会停在一段
   * 永远不再更新、也发不出去任何东西的记录上, 而没有任何东西告诉他为什么。
   *
   * <p>三个布尔答案是互斥的意图, 不是三个开关: `closeRoom` 为真时, 房间都要走了,
   * 往它的消息流里插什么已经没有意义 —— 所以 `applyToRoom` 这时恒为 false。
   */
  closeRoom: boolean
}

/**
 * @param event 事件名
 * @param data 事件体
 * @param openConversationId 当前打开的会话。`undefined` = 还没进来或还没加载完
 */
export function classifyRoomEvent(
  event: string,
  data: unknown,
  openConversationId: string | undefined,
): RoomEventEffect {
  const d = (data ?? {}) as Record<string, unknown>
  const convId = String(d.conversationId ?? '')
  const deleted = event === DELETED_EVENT

  return {
    // 销毁事件不往房间里插任何东西 —— 见 closeRoom 的说明。
    applyToRoom: !deleted && belongsToRoom(event, convId, openConversationId),
    // ★ 注意这里**没有** `&& applyToRoom`。这一句就是那个 bug 的修复本身:
    //   会话列表画的是所有会话, 所以它的刷新与"消息属于哪段会话"无关。
    refreshList: LIST_EVENTS.has(event),
    // 没带 conversationId 的销毁事件**不**关房间: 认不出它是哪一段, 贸然离开比留下更糟。
    closeRoom: deleted && !!convId && convId === openConversationId,
  }
}
