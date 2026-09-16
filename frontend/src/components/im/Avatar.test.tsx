import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { Avatar, GroupAvatar } from './Avatar'

describe('Avatar', () => {
  it('无图时显示名字首字', () => {
    expect(renderToStaticMarkup(<Avatar name="阿离" />)).toContain('阿')
  })

  it('空名字显示 ? 而不是空白方块', () => {
    expect(renderToStaticMarkup(<Avatar name="" />)).toContain('?')
  })

  it('有图时渲染 img 而不是首字', () => {
    const out = renderToStaticMarkup(<Avatar name="阿离" src="/a.png" />)
    expect(out).toContain('<img')
    expect(out).toContain('/a.png')
  })

  it('尺寸进 style —— 会话列表与通讯录用不同大小, 不能靠 class 写死', () => {
    const out = renderToStaticMarkup(<Avatar name="阿离" size={56} />)
    expect(out).toContain('width:56px')
    expect(out).toContain('height:56px')
  })

  it('同一个名字两次渲染颜色一致 —— 否则头像会在列表刷新时变脸', () => {
    const a = renderToStaticMarkup(<Avatar name="阿离" />)
    const b = renderToStaticMarkup(<Avatar name="阿离" />)
    expect(a).toBe(b)
  })

  it('agent 带角标 —— 这是与微信唯一的分野, 必须一眼看得出', () => {
    expect(renderToStaticMarkup(<Avatar name="小助手" kind="agent" />)).toContain('仿真 Agent')
  })

  it('普通用户没有角标', () => {
    expect(renderToStaticMarkup(<Avatar name="阿离" kind="user" />)).not.toContain('仿真 Agent')
  })

  it('在线时画状态点', () => {
    expect(renderToStaticMarkup(<Avatar name="阿离" online />)).toContain('在线')
  })
})

describe('GroupAvatar · 九宫格', () => {
  const members = (n: number) => Array.from({ length: n }, (_, i) => ({ name: `成员${i}` }))

  it('3 人画 3 格', () => {
    const out = renderToStaticMarkup(<GroupAvatar members={members(3)} />)
    expect(out.match(/成员/g) ?? []).toHaveLength(0) // 格子里是首字, 不是全名
    expect(out.match(/grid-template-columns/g)).toHaveLength(1)
  })

  it('12 人只画 9 格 —— 多出来的成员不画', () => {
    const out = renderToStaticMarkup(<GroupAvatar members={members(12)} />)
    // 每个格子是一个带背景色的 span; 数 gridTemplateColumns 的分母
    expect(out).toContain('repeat(3,')
  })

  it('2 人 2 列', () => {
    expect(renderToStaticMarkup(<GroupAvatar members={members(2)} />)).toContain('repeat(2,')
  })

  it('空群给一个兜底, 不是空白', () => {
    const out = renderToStaticMarkup(<GroupAvatar members={[]} />)
    expect(out).toContain('#')
  })

  it('1 人用整格', () => {
    expect(renderToStaticMarkup(<GroupAvatar members={members(1)} />)).toContain('repeat(1,')
  })
})
