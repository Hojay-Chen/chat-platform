/**
 * 会话列表的排序 —— 一期的会话列表要用, 二期加好友/群聊时是同一份规则。
 *
 * 排序规则本身很短, 单独抽出来是因为它有一条**容易被忽略但用户能立刻看出来**的性质:
 * 置顶的会话不能被新消息挤下去。用 `Array.sort` 的默认不稳定实现会随引擎版本变化,
 * 所以这里显式按 (pinned, lastMessageAt) 两级比较, 不依赖稳定性。
 */

export interface SortableConversation {
  /** 有值即为置顶 —— 用时间戳而不是布尔, 是为了保留"什么时候置顶的"（二期要按这个排同组内的序） */
  pinnedAt?: string | null
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
    const aPinned = Boolean(a.pinnedAt)
    const bPinned = Boolean(b.pinnedAt)
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
