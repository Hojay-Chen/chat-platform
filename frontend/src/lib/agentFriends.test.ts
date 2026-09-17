import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/client'
import { creationFailure, pairingNotice } from './agentFriends'

/**
 * 一键创建结果在界面上的那几句纯话。
 *
 * <p>值得测的理由只有一个: **配对码只在那一个屏幕上出现过一次**。措辞错了不会报错,
 * 只会在几天后表现为"我那个 Agent 连不上" —— 而那时没有任何线索指向这里。
 */

describe('pairingNotice', () => {
  it('有码时原样给出那串码 —— 不截断、不打码, 它是要照着敲的', () => {
    const n = pairingNotice({ pairingCode: '9GP34P', pairingCodeExpiresAt: '2026-09-17T21:10:32.123' })
    expect(n.code).toBe('9GP34P')
    expect(n.title).toBe('配对码')
  })

  /**
   * 有效时间从 `expiresAt` 读, 不写死"10 分钟"。
   *
   * 那个 10 是 `app.simulator.pairing-code-ttl-minutes` 的默认值, 是个可配项。
   * 写死之后一旦有人调成 30, 界面就开始撒谎 —— 而谎话的方向是危险的那一侧:
   * 用户会以为码已经过期而重新创建, 于是多出一个账号。
   */
  it('说"到几点为止", 而且那个时刻来自后端给的 expiresAt', () => {
    const n = pairingNotice({ pairingCode: 'ABC123', pairingCodeExpiresAt: '2026-09-17T21:10:32.123' })
    expect(n.detail).toContain('21:10')
    // 不能把 ISO 原样糊到界面上
    expect(n.detail).not.toContain('2026-09-17T21:10:32.123')
  })

  it('换了过期时刻, 那句话跟着换 —— 它读的是入参不是常量', () => {
    const n = pairingNotice({ pairingCode: 'ABC123', pairingCodeExpiresAt: '2027-01-02T07:05:00' })
    expect(n.detail).toContain('07:05')
    expect(n.detail).not.toContain('21:10')
  })

  it('时间形状不对就退回一句不需要时间的措辞 —— 而不是画一个 NaN:NaN', () => {
    const n = pairingNotice({ pairingCode: 'ABC123', pairingCodeExpiresAt: '不是时间' })
    expect(n.code).toBe('ABC123')
    expect(n.detail).not.toContain('NaN')
    expect(n.detail).not.toContain('之前有效')
  })

  /**
   * 这条分支只在"重试命中一台已配对的设备"时出现(后端 `reuseExisting` 的 ACTIVE 分支),
   * 界面上看是"我点了第二次, 它说建好了但没有码" —— 最容易被读成失败。
   * 所以那句话必须说清它不是。
   */
  it('没有码时换一句"已经配过了", 且不画空码', () => {
    const n = pairingNotice({ pairingCode: null, pairingCodeExpiresAt: null })
    expect(n.code).toBeNull()
    expect(n.title).toContain('已经配对')
    expect(n.detail).toContain('没有重建')
  })

  it('空串与只有空白的码都当"没有码"—— 它会在界面上画出一个空框', () => {
    expect(pairingNotice({ pairingCode: '' }).code).toBeNull()
    expect(pairingNotice({ pairingCode: '   ' }).code).toBeNull()
  })
})

describe('creationFailure', () => {
  it('把后端的 hint 单独取出来 —— 它才是那句可执行的话', () => {
    const f = creationFailure(
      new ApiError('Agent 平台暂不可达', 502, '稍后再试; 若持续失败请检查 http://127.0.0.1:8092'),
    )
    expect(f.message).toBe('Agent 平台暂不可达')
    expect(f.hint).toContain('稍后再试')
  })

  it('后端没给 hint 时是 null, 不是空串 —— 空串会在界面上画出一行空白', () => {
    expect(creationFailure(new ApiError('description 与 persona 必须给一个', 400, null)).hint).toBeNull()
  })

  it('网络层的 TypeError 走 message, 不编一个 hint', () => {
    const f = creationFailure(new TypeError('Failed to fetch'))
    expect(f.message).toBe('Failed to fetch')
    expect(f.hint).toBeNull()
  })

  it('连 Error 都不是时给一句不会错的话', () => {
    expect(creationFailure('炸了').message).toBe('创建失败')
  })
})
