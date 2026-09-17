import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import Capsule from './Capsule'
import ActionSheet, { SheetSection } from './ActionSheet'

/**
 * 小程序运行时那层壳 —— 胶囊与 `···` 面板。
 *
 * <h2>为什么是 renderToStaticMarkup</h2>
 * 与 `surfaces/SurfaceHost.test.tsx` 同一个理由(也同一套环境约束: 这里是 node, 没有
 * jsdom 与 testing-library)。要断言的是**结构**: 胶囊上有几个动作、有没有字、遮罩
 * 是不是一个按钮 —— 这些都是字符串能回答得比 DOM 查询更严格的问题。
 *
 * <h2>这里最要紧的一条: 胶囊上没有字</h2>
 *
 * 用户的原话是「为何还是在聊天平台通过聊天平台的栏目来进行操作」。把平台那层壳从
 * 应用头上拿掉之后, 平台在屏幕上剩下的就只有这一枚胶囊 —— 而它一旦开始写应用名、
 * 会话 id、平台名, 那层壳就又从右上角长回来了。
 *
 * 所以那条断言不是"少写几个字"的洁癖, 它是这次改动的**目的**本身: 平台不介绍自己。
 */

const noop = () => {}

function renderCapsule(busy = false) {
  return renderToStaticMarkup(<Capsule onMore={noop} onLeave={noop} busy={busy} />)
}

/** 把标签全剥掉, 只留可见文本 —— 用来问"这一块里有没有字"。 */
function visibleText(html: string): string {
  return html.replace(/<[^>]*>/g, '').trim()
}

describe('Capsule · 小程序在屏幕上唯一的那枚平台痕迹', () => {
  it('两个动作都在: 更多(···) 与 离开(⊙)', () => {
    const html = renderCapsule()
    expect(html).toContain('data-testid="mini-capsule"')
    expect(html).toContain('aria-label="更多"')
    expect(html).toContain('aria-label="离开小程序"')
    // 两个 `<button`, 不是三个也不是一个 —— 去重后的动作集合就是这两个
    expect(html.match(/<button/g)?.length).toBe(2)
  })

  it('胶囊上没有一个字', () => {
    // 见文件头。图标是 `<svg>`, 剥完标签之后应当什么都不剩。
    expect(visibleText(renderCapsule())).toBe('')
  })

  it('busy 时两个动作都按不动', () => {
    // 请求在飞的时候还能再点一次"离开", 会发出第二条 leave —— 那是重复提交, 不是热心。
    // 数 `disabled=""` 而不是 `disabled`: 按钮的 class 里本来就有个 `disabled:opacity-40`,
    // 数宽松的那个会把两处样式名也算成两个按钮。
    expect(renderCapsule(true).match(/disabled=""/g)?.length).toBe(2)
    expect(renderCapsule(false)).not.toContain('disabled=""')
  })
})

describe('ActionSheet · 胶囊上 ··· 按下去出来的那张', () => {
  const sheet = renderToStaticMarkup(
    <ActionSheet title="井字棋" subtitle="这一场 sess-123…" onClose={noop}>
      <SheetSection label="参与者 · 2">
        <span>usr-a</span>
      </SheetSection>
    </ActionSheet>,
  )

  it('标题、副标题与各段内容都渲染出来', () => {
    expect(sheet).toContain('data-testid="mini-sheet"')
    expect(sheet).toContain('井字棋')
    expect(sheet).toContain('sess-123…')
    expect(sheet).toContain('参与者 · 2')
    expect(sheet).toContain('usr-a')
  })

  it('遮罩是一个按钮, 不是一块只会响应鼠标的 div', () => {
    // 一个只能用鼠标关掉的浮层, 对键盘用户就是一个陷阱。与 Contacts 的 AddMenu 同一条规矩。
    expect(sheet).toContain('aria-label="收起"')
    expect(sheet).toContain('<button')
  })

  it('没有副标题时不留一行空的', () => {
    const bare = renderToStaticMarkup(
      <ActionSheet title="井字棋" onClose={noop}>
        <span>正文</span>
      </ActionSheet>,
    )
    expect(bare).not.toContain('text-ink-faint mt-0.5')
    expect(bare).toContain('正文')
  })
})
