import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { EmptyState, SearchBar } from './EmptyState'

const noop = () => {}

describe('EmptyState', () => {
  it('标题总要画', () => {
    expect(renderToStaticMarkup(<EmptyState title="还没有会话" />)).toContain('还没有会话')
  })

  it('没有 hint 就不渲染那一段, 不留空档', () => {
    expect(renderToStaticMarkup(<EmptyState title="x" />)).not.toContain('max-w-xs')
  })

  it('没有 action 就不渲染按钮区', () => {
    expect(renderToStaticMarkup(<EmptyState title="x" />)).not.toContain('mt-2')
  })

  it('有 action 时透传', () => {
    const out = renderToStaticMarkup(<EmptyState title="x" action={<button>去添加</button>} />)
    expect(out).toContain('去添加')
  })
})

describe('SearchBar', () => {
  it('placeholder 可换, 默认是「搜索」', () => {
    expect(renderToStaticMarkup(<SearchBar value="" onChange={noop} />)).toContain('placeholder="搜索"')
    expect(
      renderToStaticMarkup(<SearchBar value="" onChange={noop} placeholder="搜索 Agent" />),
    ).toContain('placeholder="搜索 Agent"')
  })

  it('空值时没有清除键 —— 点了什么也不会发生的按钮不该在', () => {
    expect(renderToStaticMarkup(<SearchBar value="" onChange={noop} onClear={noop} />)).not.toContain('清除')
  })

  it('有值且有 onClear 时才画清除键', () => {
    expect(
      renderToStaticMarkup(<SearchBar value="阿离" onChange={noop} onClear={noop} />),
    ).toContain('清除')
  })

  it('有值但没有 onClear 时也不画 —— 画了就要求调用方必须处理清除', () => {
    expect(renderToStaticMarkup(<SearchBar value="阿离" onChange={noop} />)).not.toContain('清除')
  })
})
