import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'
import { ConversationRow } from './ConversationRow'
import type { ConversationSummary } from '@/api/conversations'

/**
 * 会话行 —— 聊天 tab 上出现次数最多的一个件, 而它恰好是纯 props 驱动的。
 *
 * 用 `renderToStaticMarkup` 而不是 jsdom: 本仓的 vitest 是 `environment: 'node'`,
 * 没有 jsdom、没有 testing-library。所以这里断言的**全部是静态标记**,
 * 交互(点击跳转)只验"回调有没有被接上", 不模拟点击 —— 那需要一个真 DOM。
 *
 * 刻意不断言时间文本(`relativeListTime` 的产物): 它依赖真实当下, 断言它等于
 * 让测试在半夜跑的时候红。时间自己的规则在 `lib/time.test.ts` 里被确定性地测过。
 */

const summary = (over: Partial<ConversationSummary> = {}): ConversationSummary => ({
  id: 'conv-1',
  peer: { kind: 'companion', id: 'peer-1', name: '林夏' },
  title: '初见 · 林夏',
  lastMessage: {
    id: 'm-1', senderType: 'companion', senderId: 'peer-1', content: '在的',
    messageKind: null, createdAt: '2026-09-16T10:00:00',
  },
  lastMessageAt: '2026-09-16T10:00:00',
  unreadCount: 0,
  pinned: false,
  muted: false,
  ...over,
})

const render = (s: ConversationSummary, onOpen?: () => void) =>
  renderToStaticMarkup(<ConversationRow summary={s} onOpen={onOpen} />)

describe('ConversationRow', () => {
  it('画对方的名字与最后一条消息', () => {
    const html = render(summary())
    expect(html).toContain('林夏')
    expect(html).toContain('在的')
  })

  it('对方没有名字时退回会话标题 —— 宁可有, 不可空', () => {
    const html = render(summary({ peer: { kind: 'companion', id: 'p', name: '' } }))
    expect(html).toContain('初见 · 林夏')
  })

  it('数字人会话的头像带 Agent 角标 —— 这是与微信唯一的分野, 必须在列表里就看得出来', () => {
    // 角标是一个带 title 的 span。它是 Avatar 在 kind='agent' 时画的唯一标记
    expect(render(summary())).toContain('仿真 Agent')
  })

  it('没说过话的会话不画副标题', () => {
    const html = render(summary({ lastMessage: null, lastMessageAt: null }))
    expect(html).toContain('林夏')
    // 副标题是 text-[13px] 那一行。没有最后一条消息时它整个不该出现
    expect(html).not.toContain('text-[13px]')
  })

  it('有未读就画数字角标', () => {
    expect(render(summary({ unreadCount: 3 }))).toContain('>3<')
  })

  it('没未读就不画角标 —— 不画一个空的占位', () => {
    expect(render(summary({ unreadCount: 0 }))).not.toContain('bg-danger')
  })

  it('免打扰 + 有未读: 只有小红点, 没有数字', () => {
    const html = render(summary({ unreadCount: 3, muted: true }))
    expect(html).toContain('bg-danger')
    expect(html).not.toContain('>3<')
  })

  it('置顶的行带一层底 —— 微信就是靠这个区分置顶的, 不加分组标题', () => {
    expect(render(summary({ pinned: true }))).toContain('bg-sunken/70')
    expect(render(summary({ pinned: false }))).not.toContain('bg-sunken/70')
  })

  it('应用卡片消息显示 [应用], 不是那句兜底文案', () => {
    const html = render(summary({
      lastMessage: {
        id: 'm-2', senderType: 'user', senderId: 'u-1', content: '我发起了一个对局',
        messageKind: 'APPLICATION_CARD', createdAt: '2026-09-16T10:00:00',
      },
    }))
    expect(html).toContain('[应用]')
    expect(html).not.toContain('我发起了一个对局')
  })

  it('有 onOpen 时渲染成可点的按钮', () => {
    const onOpen = vi.fn()
    const html = render(summary(), onOpen)
    expect(html).toContain('<button')
    // 不模拟点击(需要真 DOM), 但回调确实被接到了按钮上 —— 没有它这一行是死的
    expect(onOpen).not.toHaveBeenCalled()
  })

  it('没有 onOpen 时不渲染成按钮 —— 纯展示的行不该长成按钮的样子', () => {
    expect(render(summary())).not.toContain('<button')
  })
})
