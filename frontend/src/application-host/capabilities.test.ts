import { describe, expect, it } from 'vitest'
import { CAPABILITIES } from './protocol'
import {
  HOST_PERMISSIONS,
  isAllowed,
  missingPermission,
  surfaceGrants,
  type HostPermission,
} from './capabilities'
import type { SurfaceType } from '@/api/lap'

/**
 * 「谁被允许做什么」—— 这一层里唯一一处**错了不会报错**的东西。
 *
 * <p>判宽了, 一个嵌在别人页面里的第三方应用会拿到一整块聊天浮窗; 判窄了, 用户在游戏里点"分享"
 * 会得到一句"没有权限", 而没人知道为什么。两种都不抛异常 —— 所以只能靠这个文件。
 */

const ALL_SURFACES: SurfaceType[] = ['FULL_PAGE', 'EMBEDDED', 'MODAL', 'PANEL', 'INLINE']

describe('surfaceGrants —— 权限来自容器, 不来自清单', () => {
  it('整页 / 弹窗 / 抽屉: 分享与浮窗都给', () => {
    for (const s of ['FULL_PAGE', 'MODAL', 'PANEL'] as const) {
      expect(surfaceGrants(s).sort()).toEqual(['CHAT_OVERLAY', 'SHARE'])
    }
  })

  it('EMBEDDED 与 INLINE 什么都不给', () => {
    // 它们不是"小一点的整页", 而是**别人页面里的一块** —— 在那种容器里浮一个全平台的
    // 聊天窗, 是宿主在越自己的权。
    expect(surfaceGrants('EMBEDDED')).toEqual([])
    expect(surfaceGrants('INLINE')).toEqual([])
  })

  it('五种容器都有判据 —— 没有"没声明过"的容器', () => {
    // 漏一种的话, GRANTS 查不到会走 ?? [] 兜底, 症状是那个容器静默地什么都不给。
    for (const s of ALL_SURFACES) {
      expect(() => surfaceGrants(s)).not.toThrow()
    }
    expect(surfaceGrants('FULL_PAGE')).not.toEqual(surfaceGrants('INLINE'))
  })

  it('返回的是副本 —— 调用方改它不该改到规则本身', () => {
    surfaceGrants('FULL_PAGE').push('SHARE')
    expect(surfaceGrants('FULL_PAGE')).toHaveLength(2)
  })
})

describe('isAllowed / missingPermission —— 一张表的两面', () => {
  it('share.request 要 SHARE', () => {
    expect(isAllowed('share.request', ['SHARE'])).toBe(true)
    expect(isAllowed('share.request', ['CHAT_OVERLAY'])).toBe(false)
    expect(missingPermission('share.request', ['CHAT_OVERLAY'])).toBe('SHARE')
  })

  it('三个浮窗能力都要 CHAT_OVERLAY', () => {
    for (const c of ['chat.openWindow', 'chat.minimize', 'chat.openConversation'] as const) {
      expect(isAllowed(c, ['CHAT_OVERLAY'])).toBe(true)
      expect(isAllowed(c, ['SHARE'])).toBe(false)
      expect(missingPermission(c, [])).toBe('CHAT_OVERLAY')
    }
  })

  it('四个不需要权限的能力在任何容器里都成立', () => {
    // app.ready / app.close / user.me 是"任何容器里都成立"的三个; session.context 也在
    // 这里, 而且是刻意的 —— 应用要**先能问出自己有什么权限**, 才谈得上权限检查。
    // 把上下文查询本身设成需要权限, 会让应用永远拿不到那个答案。
    for (const c of ['app.ready', 'app.close', 'user.me', 'session.context'] as const) {
      expect(isAllowed(c, [])).toBe(true)
      expect(missingPermission(c, [])).toBeNull()
    }
  })

  it('每一个能力都有明确答案 —— 没有一个落在"看情况"上', () => {
    // 这一条钉的是**覆盖完整性**: 将来往 CAPABILITIES 里加一个能力却忘了在 REQUIRED 里
    // 表态时, 它会默认放行 —— 而"默认放行"正是这一层最不该有的默认值。
    // 所以这里逐个点名, 加能力的人必须回来改这张表。
    const NEEDS: Record<string, HostPermission | null> = {
      'app.ready': null,
      'app.close': null,
      'user.me': null,
      'session.context': null,
      'chat.openWindow': 'CHAT_OVERLAY',
      'chat.minimize': 'CHAT_OVERLAY',
      'chat.openConversation': 'CHAT_OVERLAY',
      'share.request': 'SHARE',
    }
    expect(Object.keys(NEEDS).sort()).toEqual([...CAPABILITIES].sort())
    for (const [capability, needed] of Object.entries(NEEDS)) {
      const c = capability as (typeof CAPABILITIES)[number]
      expect(missingPermission(c, []), capability).toBe(needed)
      expect(isAllowed(c, needed ? [needed] : []), capability).toBe(true)
    }
  })

  it('权限词表只有两项 —— 加一项就是改协议', () => {
    expect(HOST_PERMISSIONS).toEqual(['SHARE', 'CHAT_OVERLAY'])
  })
})

describe('容器 × 能力 —— 那张表本身', () => {
  /**
   * 五种容器里每一个能力的实际裁决。
   *
   * <p>写成一张**逐格**的表而不是几条规则, 是因为这些格子的值就是验收标准: 改了 GRANTS
   * 或 REQUIRED 里的任何一处, 这里都会有格子变红, 而红的那一格直接告诉你"哪个容器里的
   * 哪个能力"变了 —— 而不是让人自己去推。
   */
  const EXPECTED: Record<SurfaceType, Record<string, boolean>> = {
    FULL_PAGE: {
      'app.ready': true,
      'app.close': true,
      'user.me': true,
      'session.context': true,
      'chat.openWindow': true,
      'chat.minimize': true,
      'chat.openConversation': true,
      'share.request': true,
    },
    MODAL: {
      'app.ready': true,
      'app.close': true,
      'user.me': true,
      'session.context': true,
      'chat.openWindow': true,
      'chat.minimize': true,
      'chat.openConversation': true,
      'share.request': true,
    },
    PANEL: {
      'app.ready': true,
      'app.close': true,
      'user.me': true,
      'session.context': true,
      'chat.openWindow': true,
      'chat.minimize': true,
      'chat.openConversation': true,
      'share.request': true,
    },
    // 别人页面里的一块: 只有"我是谁 / 我在哪 / 我画好了 / 我要关了"这四件事。
    EMBEDDED: {
      'app.ready': true,
      'app.close': true,
      'user.me': true,
      'session.context': true,
      'chat.openWindow': false,
      'chat.minimize': false,
      'chat.openConversation': false,
      'share.request': false,
    },
    INLINE: {
      'app.ready': true,
      'app.close': true,
      'user.me': true,
      'session.context': true,
      'chat.openWindow': false,
      'chat.minimize': false,
      'chat.openConversation': false,
      'share.request': false,
    },
  }

  it('逐格一致', () => {
    for (const surface of ALL_SURFACES) {
      const granted = surfaceGrants(surface)
      const row = EXPECTED[surface]
      expect(Object.keys(row).sort()).toEqual([...CAPABILITIES].sort())
      for (const [capability, allowed] of Object.entries(row)) {
        const c = capability as (typeof CAPABILITIES)[number]
        expect(isAllowed(c, granted), `${surface} × ${capability}`).toBe(allowed)
      }
    }
  })

  it('EMBEDDED 里被拒时, 缺的权限名是能读出来的', () => {
    // 回给应用的码是 `PERMISSION_DENIED:SHARE` 这种形状 —— 所以缺哪一项必须是个真值,
    // 而不是 null(那会拼出 `PERMISSION_DENIED` 一句没有信息的话)。
    expect(missingPermission('share.request', surfaceGrants('EMBEDDED'))).toBe('SHARE')
    expect(missingPermission('chat.openWindow', surfaceGrants('INLINE'))).toBe('CHAT_OVERLAY')
  })
})
