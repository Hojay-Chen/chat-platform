import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { AlphabetIndex } from './AlphabetIndex'

describe('AlphabetIndex', () => {
  it('只出传进来的字母, 不是 A–Z 全集', () => {
    // 画满 26 个字母看着整齐, 但用户点「Q」却什么也没发生 —— 那是骗人的可点击区域
    const out = renderToStaticMarkup(<AlphabetIndex letters={['A', 'C', 'Z']} />)
    expect(out).toContain('>A<')
    expect(out).toContain('>C<')
    expect(out).toContain('>Z<')
    expect(out).not.toContain('>B<')
    expect(out).not.toContain('>Q<')
  })

  it('没有字母时整个不渲染', () => {
    expect(renderToStaticMarkup(<AlphabetIndex letters={[]} />)).toBe('')
  })

  it('当前字母高亮', () => {
    const out = renderToStaticMarkup(<AlphabetIndex letters={['A', 'B']} active="B" />)
    expect(out).toContain('bg-accent')
  })

  it('没有 onPick 时按钮不可点', () => {
    expect(renderToStaticMarkup(<AlphabetIndex letters={['A']} />)).not.toContain('onClick')
  })
})
