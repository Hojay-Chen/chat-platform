/**
 * 一条消息在界面上的状态 —— 从 `Chat.tsx:937-967` 抽出来的。
 *
 * `userStatus` 的五个分支（已读 / 发送中 / 发送失败 / 已发送 / 平台侧状态）
 * 原先埋在组件里, 从没被测过。而它恰好是最容易出错的一处:
 * 把"发送失败"错判成"已发送", 用户会以为消息出去了。
 */

/** 与 `types.Message` 结构兼容的最小子集 —— 抽出来是为了不依赖 types/ */
export interface StatusSource {
  id: string
  deliveryStatus?: string | null
}

/**
 * 自己发出去的消息显示什么状态。
 *
 * 判定顺序是有意的: **本地乐观状态优先于服务端状态**。`readMap` 是 SSE 推来的
 * 已读回执, `statusMap` 是本地乐观更新（SENT / FAILED）。服务端的
 * `deliveryStatus` 只在两者都没有时才作数 —— 因为它可能还是落库时的初始值,
 * 而用户刚刚明明看着它失败了。
 */
export function userStatus(
  m: StatusSource,
  readMap: Readonly<Record<string, boolean>>,
  statusMap: Readonly<Record<string, string>>,
): string {
  if (readMap[m.id]) return '已读'

  const st = statusMap[m.id] || m.deliveryStatus
  if (st === 'READ' || st === 'DEFERRED' || st === 'RESPONDED') return '已读'
  if (st === 'SENT') return '发送中'
  if (st === 'FAILED') return '发送失败'
  return '已发送'
}

/**
 * 事件 → Message 对象的增量 upsert 形状。
 *
 * `createdAt` 用本地时钟而不是服务端时间: 这个对象只用于**立刻**插进消息流
 * （随后会被 `message_created` 事件里的 canonical 消息按 id 替换掉）。
 * 用本地时钟是为了让它排在列表末尾 —— 若服务端与浏览器有时差,
 * 用服务端时间会让这条新消息插到历史中间去。
 */
export function toMessage(
  id: string,
  conversationId: string,
  senderType: 'user' | 'companion',
  content: string,
  clientMessageId?: string,
  now: Date = new Date(),
): {
  id: string
  conversationId: string
  senderType: 'user' | 'companion'
  content: string
  clientMessageId?: string
  createdAt: string
} {
  return {
    id,
    conversationId,
    senderType,
    content,
    clientMessageId,
    createdAt: now.toISOString(),
  }
}
