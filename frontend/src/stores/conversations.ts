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
 *
 * <h2>为什么合并并发拉取</h2>
 *
 * 因为 `load()` 的调用者不止这一个 30s 的定时器: 每一条 `companion_message` /
 * `message_created` 事件也会调它(见 `lib/roomEvents.ts` 的 `refreshList`)。而事件可以
 * **成串**到达 —— SSE 在建立连接时会回放最近的若干条事件(服务端 `EventController` 的
 * `REPLAY_LIMIT`)。于是"打开一段对话"这件事会变成"每条回放事件各拉一次全量列表"。
 *
 * <p>实测(2026-09 排障): 打开一段 92 条消息的对话, 9 秒内发出 **186 次**
 * `GET /api/conversations`; 换一段只有 2 条消息的对话, 同一操作只有 1 次。
 * 这不是"多几个请求"的量级 —— 它同时占满浏览器的连接池, 而聊天页的首屏渲染正排队
 * 等在那些连接后面, 用户看到的就是"界面卡住不动"。
 *
 * <p>合并是无损的, 理由见 `load()` 的注释: 这个列表是"最后一次结果即真相"。
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

/**
 * 尾部合并的安静窗口。
 *
 * <p>不直接"在途结束后立刻补一次", 是因为事件是**按条**到的: 立刻补的话, 每一条事件
 * 又会各补一次, 186 条事件只是从 186 次拉取变成十几次 —— 好了, 但没解决问题。
 * 等一小段安静期, 才能把"这一整串突发"合成一次。
 */
const COALESCE_MS = 300

/** 在途的那一次拉取。非空即表示"现在只允许有一次"。 */
let inFlight: Promise<void> | null = null
/** 在途期间又有人要过一次 —— 落地后还欠他一次。 */
let owed = false

export const useConversationStore = create<ConversationState>((set, get) => {
  const run = async (): Promise<void> => {
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
  }

  return {
    list: [],
    loading: false,
    error: null,

    /**
     * 拉一次会话列表。**并发调用会被合并** —— 见本文件顶部"为什么合并"那一节。
     *
     * <p>语义上这是无损的: 这个列表是"最后一次结果即真相", 并发的两次拉取里先落地的那次
     * 一定会被后落地的覆盖, 所以中间那次请求除了占带宽和连接数之外不产生任何影响。
     */
    load: () => {
      if (inFlight) {
        owed = true
        return inFlight
      }
      inFlight = (async () => {
        try {
          await run()
          while (owed) {
            owed = false
            // 等安静期: 突发还没结束就再等一轮, 不结束就不发
            await new Promise((resolve) => setTimeout(resolve, COALESCE_MS))
            if (owed) continue
            await run()
          }
        } finally {
          inFlight = null
        }
      })()
      return inFlight
    },

    patch: (id, over) =>
      set((s) => ({ list: s.list.map((c) => (c.id === id ? { ...c, ...over } : c)) })),

    reset: () => set({ list: [], loading: false, error: null }),
  }
})

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
