import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { MessageBubble, TimeSeparator } from './MessageBubble'

describe('MessageBubble · 三种角色', () => {
  it('自己的消息靠右', () => {
    expect(renderToStaticMarkup(<MessageBubble sender="user" content="你好" />)).toContain('flex-row-reverse')
  })

  it('对方的消息靠左', () => {
    expect(renderToStaticMarkup(<MessageBubble sender="peer" content="在的" />)).toContain('flex-row')
  })

  it('系统消息居中, 不带头像位置', () => {
    const out = renderToStaticMarkup(<MessageBubble sender="system" content="你撤回了一条消息" />)
    expect(out).toContain('justify-center')
    expect(out).not.toContain('flex-row-reverse')
  })

  it('自己与对方用不同气泡色', () => {
    expect(renderToStaticMarkup(<MessageBubble sender="user" content="x" />)).toContain('bg-bubble-out')
    expect(renderToStaticMarkup(<MessageBubble sender="peer" content="x" />)).toContain('bg-bubble-in')
  })
})

describe('MessageBubble · 细节', () => {
  it('状态文本透传', () => {
    expect(renderToStaticMarkup(<MessageBubble sender="user" content="x" status="已读" />)).toContain('已读')
  })

  it('没有 onRetry 时不画重试按钮 —— 点了没反应的按钮比没有更糟', () => {
    const out = renderToStaticMarkup(<MessageBubble sender="user" content="x" failed />)
    expect(out).not.toContain('重发')
  })

  it('有 onRetry 时才画', () => {
    const out = renderToStaticMarkup(
      <MessageBubble sender="user" content="x" failed onRetry={() => {}} />,
    )
    expect(out).toContain('重发')
  })

  it('群聊里显示发送者名字 —— 一期不传, 二期群聊传', () => {
    expect(
      renderToStaticMarkup(<MessageBubble sender="peer" content="x" senderName="张三" />),
    ).toContain('张三')
  })

  it('不传 senderName 时不留空档', () => {
    expect(renderToStaticMarkup(<MessageBubble sender="peer" content="x" />)).not.toContain('text-ink-faint">')
  })

  it('正在输入且无内容时画三点, 不画空气泡', () => {
    const out = renderToStaticMarkup(<MessageBubble sender="peer" content="" streaming />)
    expect(out).toContain('typing-dot')
    // 气泡本体带 whitespace-pre-wrap, 三点指示器不带 —— 用这个区分, 而不是用底色
    // （三点指示器的容器也用 bg-bubble-in, 它就该和对方气泡同色）
    expect(out).not.toContain('whitespace-pre-wrap')
  })

  it('已有内容时即使 streaming 也画内容', () => {
    const out = renderToStaticMarkup(<MessageBubble sender="peer" content="我正在想" streaming />)
    expect(out).toContain('我正在想')
    expect(out).not.toContain('typing-dot')
  })

  it('换行被保留 —— 不用 white-space:nowrap 之外的手段', () => {
    expect(renderToStaticMarkup(<MessageBubble sender="peer" content={'第一行\n第二行'} />))
      .toContain('whitespace-pre-wrap')
  })
})

describe('TimeSeparator', () => {
  it('渲染标签', () => {
    expect(renderToStaticMarkup(<TimeSeparator label="昨天" />)).toContain('昨天')
  })
})
