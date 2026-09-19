import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'

/**
 * `useConversationStore.load()` —— **一次突发只能换一次拉取**。
 *
 * <h2>为什么这件事值得一个测试文件</h2>
 *
 * 这个 store 的注释里写着"只轮询这一个列表, 30s 一次", 读起来像一个温和的实现。但
 * `load()` 的调用者不止那个定时器: 每一条 `companion_message` / `message_created` 事件
 * 也会调它(`lib/roomEvents.ts` 的 `refreshList`), 而事件会**成串**到达 —— SSE 在建立
 * 连接时回放最近的若干条。于是"打开一段对话"变成了"每条回放事件各拉一次全量列表"。
 *
 * <p>实测过: 打开一段 92 条消息的对话, 9 秒内 **186 次** `GET /api/conversations`;
 * 换一段 2 条消息的, 同一操作只有 1 次。首屏因此排在几十个请求后面, 用户看到的是
 * "界面卡住不动" —— 而那正是这次要修的东西。
 *
 * <h2>这里钉住的性质有三条, 少一条都不够</h2>
 *
 * <ol>
 *   <li><b>突发只换一次拉取</b> —— 这是修复本身。</li>
 *   <li><b>不突发不补拉</b> —— 合并**不能**给那条 30s 的常规轮询平白加一次尾部请求,
 *       否则等于把轮询频率翻倍。这一条是"修复没有副作用"的证据。</li>
 *   <li><b>安静期里又来事件 → 安静期重来, 但仍然只补一次</b> —— 否则突发只要比安静期
 *       长, 就又退化成"每条事件一次"。</li>
 * </ol>
 *
 * <p>模块级的 `inFlight` / `owed` 是刻意的(它要跨调用者共享), 所以每个用例都得
 * `resetModules` 后重新 import —— 否则上一个用例的在途状态会漏进下一个。
 */

let listConversations: Mock
let store: typeof import('./conversations')['useConversationStore']

const row = (id: string) => ({
  id,
  peer: { kind: 'companion' as const, id: 'peer-1', name: '林夏' },
  title: '初见 · 林夏',
  lastMessage: undefined,
  unreadCount: 0,
  pinned: false,
  muted: false,
})

beforeEach(async () => {
  vi.resetModules()
  vi.useFakeTimers()
  listConversations = vi.fn().mockResolvedValue([row('conv-1')])
  vi.doMock('@/api/conversations', () => ({ listConversations }))
  store = (await import('./conversations')).useConversationStore
})

afterEach(() => {
  vi.useRealTimers()
  vi.doUnmock('@/api/conversations')
})

describe('useConversationStore.load · 并发合并', () => {
  it('★ 186 次并发只换 2 次拉取(1 次立即 + 1 次尾部合并), 而不是 186 次', async () => {
    // 模拟那条真实的突发: 事件成串到达, 全部落在第一次拉取在途的那段时间里
    const burst = Promise.all(Array.from({ length: 186 }, () => store.getState().load()))
    await vi.advanceTimersByTimeAsync(1000)
    await burst

    // 恰好两次: 第一次, 加上"在途期间有人要过"带来的那一次尾部补拉。
    // 上限写成 2 而不是"小于 186", 是因为这个数字本身就是要守的性质。
    expect(listConversations).toHaveBeenCalledTimes(2)
  })

  it('不突发就不补拉 —— 那条 30s 的常规轮询不该被加上一次尾部请求', async () => {
    await store.getState().load()
    await vi.advanceTimersByTimeAsync(5000)

    expect(listConversations).toHaveBeenCalledTimes(1)
  })

  it('安静期里又来事件 → 安静期重来, 仍然只补一次', async () => {
    // 第一次真的卡在在途 —— 不这样做的话 mock 会立刻返回, 安静期根本不会开始
    let release!: (v: unknown) => void
    listConversations.mockImplementationOnce(() => new Promise((r) => { release = r }))

    const first = store.getState().load()
    await vi.advanceTimersByTimeAsync(0)
    void store.getState().load() // 在途期间要过一次 → 落地后欠一次
    release([row('conv-1')])
    await vi.advanceTimersByTimeAsync(0) // 第 1 次落地, 进入安静期

    await vi.advanceTimersByTimeAsync(200) // 安静期走了 200ms
    void store.getState().load() // 又来了 → 窗口应当从这一刻重算
    await vi.advanceTimersByTimeAsync(200) // 距上次调用 200ms, 还没安静够
    expect(listConversations).toHaveBeenCalledTimes(1) // 所以还没补

    await vi.advanceTimersByTimeAsync(200) // 现在安静够了 → 补这一次
    await first
    expect(listConversations).toHaveBeenCalledTimes(2)
  })

  it('列表落地了 —— 合并没有把数据吞掉', async () => {
    const burst = Promise.all([store.getState().load(), store.getState().load()])
    await vi.advanceTimersByTimeAsync(1000)
    await burst

    expect(store.getState().list.map((c) => c.id)).toEqual(['conv-1'])
    expect(store.getState().loading).toBe(false)
  })

  it('拉取失败也不清空已有列表 —— 网络抖一下不该让整屏聊天记录消失', async () => {
    await store.getState().load()
    expect(store.getState().list).toHaveLength(1)

    listConversations.mockRejectedValueOnce(new Error('网络抖了一下'))
    const p = store.getState().load()
    await vi.advanceTimersByTimeAsync(1000)
    await p

    expect(store.getState().list).toHaveLength(1)
    expect(store.getState().error).toBe('网络抖了一下')
  })
})
