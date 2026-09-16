import { create } from 'zustand'
import type { ConversationSummary } from '@/api/conversations'
import * as convApi from '@/api/conversations'
import { totalUnread } from '@/lib/conversations'

/**
 * 会话列表的客户端状态。
 *
 * <h2>为什么轮询, 而不是开 N 条 SSE</h2>
 *
 * 今天的事件流是 `openEventStream(companionId)` —— **按伴侣**开的, 一条连接覆盖该伴侣下
 * 的所有会话。聊天 tab 要的是"我所有会话的未读", 拿不到。
 *
 * 能立刻想到的做法是"有几个伴侣就开几条", 而那是错的: 二期加好友、加群之后, 连接数
 * 随会话数增长。正解是后端提供一条按用户的 `GET /api/events`(事件带 conversationId),
 * 但那要等一期之后。
 *
 * 所以一期**只轮询这一个列表**, 频率与今天 `Chat.tsx` 里那个 `loadUnread` 相同(30s)。
 * 轮询是一期可接受的代价; 二期换成 SSE 时, 这个 store 的对外签名一个字都不用改 ——
 * 变的只是谁来调 `setList`。
 */

interface ConversationState {
  list: ConversationSummary[]
  /** 首屏加载中 —— 只有第一次为 true, 后续轮询不该让列表闪成"加载中" */
  loading: boolean
  error: string | null
  load: () => Promise<void>
  /** 就地替换一行(置顶/免打扰/已读之后) —— 不必为一次点击重拉整个列表 */
  patch: (id: string, over: Partial<ConversationSummary>) => void
  reset: () => void
}

export const useConversationStore = create<ConversationState>((set, get) => ({
  list: [],
  loading: false,
  error: null,

  load: async () => {
    // 只有还没有数据时才显示"加载中" —— 轮询刷新时把已有列表换成骨架屏是最刺眼的一种抖动
    if (get().list.length === 0) set({ loading: true })
    try {
      set({ list: await convApi.listConversations(), error: null })
    } catch (e) {
      // 轮询失败不弹错、不清空列表: 网络抖一下就让整屏聊天记录消失, 比显示旧数据糟糕得多
      set({ error: e instanceof Error ? e.message : '加载失败' })
    } finally {
      set({ loading: false })
    }
  },

  patch: (id, over) =>
    set((s) => ({ list: s.list.map((c) => (c.id === id ? { ...c, ...over } : c)) })),

  reset: () => set({ list: [], loading: false, error: null }),
}))

/**
 * TabBar 上「聊天」那个红点。
 *
 * 做成 selector 而不是 store 里的一个 `totalUnread` 字段: 后者要在每次 `patch` 之后
 * 手动重算, 而"忘了重算"的表现是角标数字不动 —— 一个没人会立刻发现的 bug。
 * 派生出来就不可能忘。
 */
export function useTotalUnread(): number {
  return useConversationStore((s) => totalUnread(s.list))
}
