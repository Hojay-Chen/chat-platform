import { describe, expect, it } from 'vitest'
import { toSummary } from './conversations'

/**
 * 翻译层是这一期**最值得测**的一处, 而它恰好是纯粹的 —— `toSummary` 不吃 fetch、不吃
 * DOM, 所以能被直接断言。这正是把翻译从请求函数里拆出来的理由: 若它埋在
 * `listConversations()` 的函数体里, 要测它就得先 mock 掉 `fetch` 和 localStorage。
 *
 * 二期加真人会话与群聊时, 这个文件会多出几条 `kind` 分支的断言, 而实现只需要改
 * `toSummary` 一个 switch。
 */

const dto = (over: Partial<Parameters<typeof toSummary>[0]> = {}) => ({
  id: 'conv-1',
  peerId: 'peer-1',
  peerName: '林夏',
  title: '初见 · 林夏',
  messageCount: 3,
  unreadCount: 0,
  pinned: false,
  muted: false,
  ...over,
})

describe('toSummary · 服务端一行 → 界面一行', () => {
  it('peerId 翻译成 PeerRef, 一期 kind 恒为 companion', () => {
    const s = toSummary(dto())
    expect(s.peer).toEqual({ kind: 'companion', id: 'peer-1', name: '林夏' })
  })

  it('界面类型里没有 peerId 这个字段名 —— 它就是不该被界面看见', () => {
    expect(toSummary(dto())).not.toHaveProperty('peerId')
  })

  it('最后一条消息原样带过来, 含 senderId', () => {
    const s = toSummary(dto({
      lastMessage: {
        id: 'm-1', senderType: 'user', senderId: 'u-1', content: '在吗',
        messageKind: null, createdAt: '2026-09-16T10:00:00',
      },
    }))
    expect(s.lastMessage?.content).toBe('在吗')
    expect(s.lastMessage?.senderId).toBe('u-1')
  })

  it('从没说过话的会话, lastMessage 与 lastMessageAt 都是 null 而不是 undefined', () => {
    // 后端在"这个会话还没有消息"时这两个字段整个不出现在 JSON 里(Jackson 的默认行为),
    // 于是它们是 undefined。界面代码写 `?.` 和写 `!== null` 是两种不同的判断,
    // 这里统一收敛成 null, 让下游只需要判一种。
    const s = toSummary(dto({ lastMessage: undefined, lastMessageAt: undefined }))
    expect(s.lastMessage).toBeNull()
    expect(s.lastMessageAt).toBeNull()
  })

  it('缺 unreadCount 时当 0 —— 不显示 NaN', () => {
    const s = toSummary(dto({ unreadCount: undefined }))
    expect(s.unreadCount).toBe(0)
  })

  it('pinned / muted 收敛成布尔', () => {
    expect(toSummary(dto()).pinned).toBe(false)
    expect(toSummary(dto({ pinned: true, muted: true }))).toMatchObject({
      pinned: true, muted: true,
    })
  })
})
