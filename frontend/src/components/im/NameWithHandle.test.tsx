import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { NameWithHandle } from './NameWithHandle'

/**
 * 「名字 + 账号ID」这一小段 —— 聊天列表与通讯录行共用的那一个。
 *
 * <p>它小到几乎没有逻辑, 但它是"7 个一模一样的「小满」"这个问题的**全部答案**,
 * 所以值得有断言: 名字还在、账号ID 出来了、没有账号ID 时**什么都不多画**。
 */
describe('NameWithHandle', () => {
  it('名字与账号ID 都画出来', () => {
    const html = renderToStaticMarkup(<NameWithHandle name="小满" handle="k3f9d2m1pq" />)
    expect(html).toContain('小满')
    expect(html).toContain('k3f9d2m1pq')
  })

  it('账号ID 用等宽字 —— 它是要被念出来、照着敲的', () => {
    expect(renderToStaticMarkup(<NameWithHandle name="小满" handle="k3f9d2m1pq" />))
      .toContain('font-mono')
  })

  /**
   * 老数据(补号还没跑)和新数据(已补号)必须长得**一样干净**。
   * 画一个空的灰块会让用户以为加载失败了。
   */
  it('没有账号ID 时只画名字, 不留空块', () => {
    for (const missing of [null, undefined, '']) {
      const html = renderToStaticMarkup(<NameWithHandle name="小满" handle={missing} />)
      expect(html).toBe('小满')
    }
  })

  it('名字很长也不会把账号ID 挤掉 —— 账号ID 是 shrink-0', () => {
    const html = renderToStaticMarkup(
      <NameWithHandle name={'很长'.repeat(30)} handle="k3f9d2m1pq" />)
    expect(html).toContain('shrink-0')
    expect(html).toContain('k3f9d2m1pq')
  })

  /** 长名字要被截断而不是撑破行 —— 少了 `min-w-0`, flex 子项会拒绝收缩。 */
  it('名字那一侧是可截断的', () => {
    expect(renderToStaticMarkup(<NameWithHandle name="小满" handle="abc123" />))
      .toContain('min-w-0')
  })
})
