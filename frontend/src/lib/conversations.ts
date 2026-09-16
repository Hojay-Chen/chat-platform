/**
 * 会话列表的排序与摘要 —— 一期的会话列表要用, 二期加好友/群聊时是同一份规则。
 *
 * 排序规则本身很短, 单独抽出来是因为它有一条**容易被忽略但用户能立刻看出来**的性质:
 * 置顶的会话不能被新消息挤下去。用 `Array.sort` 的默认不稳定实现会随引擎版本变化,
 * 所以这里显式按 (pinned, lastMessageAt) 两级比较, 不依赖稳定性。
 */

export interface SortableConversation {
  /** 置顶。服务端存的是一个时间戳(`conversation_read_state.pinned_at`), 但它**没有过期语义**,
   *  所以对外收敛成布尔 —— 与 `muted` 一致。将来真要按"什么时候置顶的"排同组内的序,
   *  那个时间戳还在库里, 加一个字段就能取回来, 信息并没有在这里丢掉。 */
  pinned?: boolean
  lastMessageAt?: string | null
}

/** 时间戳降序; 缺失的排最后（新建但还没说过话的会话不该冒到顶上） */
function descByTime(a?: string | null, b?: string | null): number {
  const ta = a ? new Date(a).getTime() : -Infinity
  const tb = b ? new Date(b).getTime() : -Infinity
  return tb - ta
}

export function sortConversations<T extends SortableConversation>(list: readonly T[]): T[] {
  return [...list].sort((a, b) => {
    const aPinned = Boolean(a.pinned)
    const bPinned = Boolean(b.pinned)
    if (aPinned !== bPinned) return aPinned ? -1 : 1
    // 同组内按最后一条消息时间。置顶组内也一样 —— 置顶不冻结顺序, 只是提升整组
    return descByTime(a.lastMessageAt, b.lastMessageAt)
  })
}

/** 未读总数 —— TabBar 上的那个红点 */
export function totalUnread(list: readonly { unreadCount?: number; muted?: boolean }[]): number {
  // 免打扰的会话不计入角标 —— 这正是"免打扰"的含义。但会话行本身仍显示小红点
  return list.reduce((sum, c) => (c.muted ? sum : sum + (c.unreadCount ?? 0)), 0)
}

/** 会话行副标题需要的最小形状 */
export interface PreviewSource {
  content: string
  messageKind?: string | null
}

/**
 * 带结构化载荷的消息在列表里显示成什么。
 *
 * 一条应用卡片消息的 `content` 是一句给人读的兜底文案(比如"我发起了一个井字棋"),
 * 但它不是这类消息的**要点** —— 要点是"这是个应用"。微信对图片/语音/文件正是这么做的:
 * 列表里显示 `[图片]`, 不显示"你发了一张图片"。
 *
 * 认不出的 `messageKind` 一律退回 `content`, 与 `ApplicationCardBubble` 的降级规则一致:
 * 前端不认识的新消息类型, 至少要让用户看见那句话, 而不是一个空白行。
 *
 * **这里刻意不列具体应用名。** 聊天前端在编译期不认识任何应用(§115), 一份写死
 * "井字棋"的映射表会让它替应用平台做决定。
 */
const KIND_LABEL: Record<string, string> = {
  APPLICATION_CARD: '[应用]',
  APPLICATION_INVITATION: '[邀请]',
  SYSTEM: '[系统消息]',
  TOOL_RESULT: '[工具结果]',
}

/** 会话行那一行的副标题。没说过话的会话给空串 —— 由调用方决定要不要画这一行 */
export function previewText(last?: PreviewSource | null): string {
  if (!last) return ''
  const label = last.messageKind ? KIND_LABEL[last.messageKind] : undefined
  return label ?? last.content ?? ''
}
