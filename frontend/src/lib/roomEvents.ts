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

export interface RoomEventEffect {
  /** 要不要作用在**当前打开的那段会话**的消息流上 */
  applyToRoom: boolean
  /** 要不要重新拉一次会话列表 */
  refreshList: boolean
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

  return {
    // 没有 conversationId 的事件按"属于当前会话"处理 —— 老事件流里有这种帧,
    // 把它们一律丢掉会让某些提示(比如 typing)永远不出现。
    applyToRoom: !convId || convId === openConversationId,
    // ★ 注意这里**没有** `&& applyToRoom`。这一句就是那个 bug 的修复本身:
    //   会话列表画的是所有会话, 所以它的刷新与"消息属于哪段会话"无关。
    refreshList: MESSAGE_EVENTS.has(event),
  }
}
