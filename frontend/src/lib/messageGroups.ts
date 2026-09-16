import { dateLabel } from './time'

/**
 * 消息流的分组 —— 从 `Chat.tsx:495-507` 抽出来的。
 *
 * 原实现把"什么时候插一条日期分隔"这条判定写在 JSX 的 `map` 回调里,
 * 于是它只有一个测试方式: 渲染整个 ChatRoom 并数 DOM 节点。抽成纯函数之后,
 * 这条规则（"标签变了才插, 而不是每 N 条插一条"）可以被直接断言。
 *
 * 用泛型而不是直接吃 `Message`, 是为了让这条纯函数不依赖 `types/`。
 */

export interface GroupableMessage {
  id: string
  createdAt: string
}

export type MessageRow<M> =
  | { kind: 'separator'; key: string; label: string }
  | { kind: 'message'; key: string; message: M }

/**
 * 把消息摊平成"分隔条 + 消息"的交替序列。
 *
 * 三条规则:
 * 1. 首条消息之前必有一条分隔（否则用户不知道第一屏是什么时候的）
 * 2. 只在**日期标签变化**处插分隔 —— 不是每隔 24 小时, 也不是每 N 条。
 *    所以同一批"昨天"的消息中间不会有第二条"昨天"。
 * 3. 空输入给空输出（不是一条孤零零的"今天"）
 */
export function groupMessages<M extends GroupableMessage>(
  messages: readonly M[],
  now: Date = new Date(),
): MessageRow<M>[] {
  const rows: MessageRow<M>[] = []
  let lastLabel: string | null = null

  for (let i = 0; i < messages.length; i++) {
    const m = messages[i]
    const label = dateLabel(m.createdAt, now)
    if (label !== lastLabel) {
      rows.push({ kind: 'separator', key: `sep-${i}`, label })
      lastLabel = label
    }
    rows.push({ kind: 'message', key: m.id, message: m })
  }

  return rows
}
