import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/client'
import { describeFailure, handleIndex, quotaLabel } from './handles'

/**
 * 账号ID 在界面上的那几件纯事。
 *
 * <p>这些函数之所以存在, 正是因为它们**必须能被这样测**: 单测跑在 `environment: 'node'`,
 * 只有 `renderToStaticMarkup` —— 留在带 `useState` 的组件里, 它们一行断言都不会有。
 */

describe('handleIndex', () => {
  it('建出 id → 账号ID 的索引', () => {
    const index = handleIndex([
      { id: 'a', handle: 'xiaoman_a1' },
      { id: 'b', handle: 'xiaoman_b2' },
    ])
    expect(index.get('a')).toBe('xiaoman_a1')
    expect(index.get('b')).toBe('xiaoman_b2')
  })

  /**
   * 老数据(加列之前建的 Person)没有账号ID, 补号跑完之前这一段是常态。
   * 把它们映射成 `null` 会让调用方多一条"是不是 null"的分支, 而那条分支处理的是
   * 和"这个人不在索引里"完全相同的情况。
   */
  it('没有账号ID 的人不进索引 —— 而不是映射成 null', () => {
    const index = handleIndex([{ id: 'a', handle: null }, { id: 'b' }, { id: 'c', handle: '' }])
    expect(index.size).toBe(0)
    expect(index.get('a')).toBeUndefined()
  })

  it('空串不算账号ID —— 它会在界面上画出一个像加载失败的空灰块', () => {
    expect(handleIndex([{ id: 'a', handle: '' }]).has('a')).toBe(false)
  })

  it('同一个 id 出现两次时后者胜 —— 索引不该因为数据重复就炸', () => {
    const index = handleIndex([{ id: 'a', handle: 'old' }, { id: 'a', handle: 'new' }])
    expect(index.get('a')).toBe('new')
  })
})

describe('quotaLabel', () => {
  it('还有额度时说次数', () => {
    expect(quotaLabel({ remaining: 2, limit: 3, nextChangeAt: null }))
      .toBe('每年可修改 3 次，还剩 2 次')
  })

  it('额度用尽时说日期 —— 那是唯一能据以行动的信息', () => {
    expect(quotaLabel({ remaining: 0, limit: 3, nextChangeAt: '2027-03-14T10:30:00' }))
      .toBe('修改次数已用完，2027-03-14 之后可以再改')
  })

  /** 只取日期: 用户关心的是"哪一天", 时分秒对他只是噪音。 */
  it('只显示日期, 不显示时分秒', () => {
    expect(quotaLabel({ remaining: 0, limit: 3, nextChangeAt: '2027-03-14T23:59:59.999' }))
      .not.toContain(':')
  })

  /**
   * 后端的契约是"额度用尽才带 nextChangeAt"(见 `HandleQuota.of`), 但界面不该因此
   * 在契约万一不成立时画出一句带 "undefined" 的话。
   */
  it('用尽却没有日期时退回一句不会错的话', () => {
    const label = quotaLabel({ remaining: 0, limit: 3, nextChangeAt: null })
    expect(label).toBe('修改次数已用完')
    expect(label).not.toContain('undefined')
    expect(label).not.toContain('null')
  })
})

describe('describeFailure', () => {
  it('400 形状不对: 把后端的说法与建议一起带出来', () => {
    const f = describeFailure(new ApiError('账号ID只能用字母、数字、下划线(_)和短横线(-)', 400,
      '可以试试 xiaoman'))
    expect(f.message).toContain('账号ID只能用字母')
    expect(f.hint).toBe('可以试试 xiaoman')
    expect(f.quotaExhausted).toBe(false)
  })

  /** 409 与 400 的区别对用户是"换一个"和"格式不对"—— 两者都要能显示 hint。 */
  it('409 被占用: 带着一个大概还能用的建议', () => {
    const f = describeFailure(new ApiError('账号ID「xiaoman」已经被占用了', 409,
      '可以试试 xiaoman_k3f'))
    expect(f.hint).toBe('可以试试 xiaoman_k3f')
    expect(f.quotaExhausted).toBe(false)
  })

  /**
   * 429 是唯一一个"此刻无论怎么改都改不动"的状态 —— 界面据此把输入框和按钮一起停掉,
   * 而不是让用户反复撞同一面墙。这是 `status` 在这里的唯一用途。
   */
  it('429 配额用尽: 标出来, 好让界面把入口停掉', () => {
    const f = describeFailure(new ApiError('账号ID 每 365 天最多修改 3 次，你已经用完了', 429,
      '下次可改时间：2027-03-14'))
    expect(f.quotaExhausted).toBe(true)
    expect(f.hint).toContain('2027-03-14')
  })

  it('没有 hint 时是 null, 不是空串 —— 界面据这个决定画不画第二行', () => {
    expect(describeFailure(new ApiError('服务器内部错误', 500, null)).hint).toBeNull()
  })

  /** 网络断了(fetch 自己抛 TypeError)时没有 status 可读, 也不该编一句后端没说过的话。 */
  it('非 ApiError 退回 message, 不编造 hint', () => {
    const f = describeFailure(new TypeError('Failed to fetch'))
    expect(f.message).toBe('Failed to fetch')
    expect(f.hint).toBeNull()
    expect(f.quotaExhausted).toBe(false)
  })

  it('抛出来的根本不是 Error 时也要给一句能显示的', () => {
    const f = describeFailure('某个字符串')
    expect(f.message).toBe('修改失败')
    expect(f.hint).toBeNull()
  })
})
