import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { ListRow, SectionHeader } from './ListRow'

describe('ListRow', () => {
  it('标题与副标题都渲染', () => {
    const out = renderToStaticMarkup(<ListRow title="阿离" subtitle="在的，怎么了" />)
    expect(out).toContain('阿离')
    expect(out).toContain('在的，怎么了')
  })

  it('没有副标题时那一行不渲染, 不留空档', () => {
    const out = renderToStaticMarkup(<ListRow title="阿离" />)
    expect(out).not.toContain('leading-5')
  })

  it('有 onClick 时是 button —— 真机能点', () => {
    expect(renderToStaticMarkup(<ListRow title="阿离" onClick={() => {}} />)).toContain('<button')
  })

  it('没有 onClick 时是 div, 不是 disabled button', () => {
    // disabled 按钮既点不动又会变灰、还吃掉键盘焦点 —— 对纯展示的行那是错的语言
    const out = renderToStaticMarkup(<ListRow title="共 12 人" />)
    expect(out).not.toContain('<button')
    expect(out).toContain('<div')
  })

  it('时间文本用等宽数字 —— 否则会话列表轮询刷新时整列会左右抖', () => {
    expect(renderToStaticMarkup(<ListRow title="阿离" trailingText="12:30" />)).toContain('tnum')
  })

  it('免打扰的行标题变灰', () => {
    const normal = renderToStaticMarkup(<ListRow title="阿离" />)
    const muted = renderToStaticMarkup(<ListRow title="阿离" muted />)
    expect(normal).toContain('text-ink')
    expect(muted).toContain('text-ink-soft')
  })

  it('选中态用淡蓝底', () => {
    expect(renderToStaticMarkup(<ListRow title="阿离" active />)).toContain('bg-accent-soft')
  })

  it('角标透传', () => {
    expect(
      renderToStaticMarkup(<ListRow title="阿离" badge={<span>99+</span>} />),
    ).toContain('99+')
  })
})

describe('SectionHeader', () => {
  it('给标签', () => {
    expect(renderToStaticMarkup(<SectionHeader label="A" />)).toContain('A')
  })

  it('给数量时才渲染数量', () => {
    expect(renderToStaticMarkup(<SectionHeader label="Agent" count={3} />)).toContain('3')
    expect(renderToStaticMarkup(<SectionHeader label="Agent" />)).not.toContain('tnum')
  })
})
