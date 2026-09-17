import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import SurfaceHost from './SurfaceHost'
import { CLIENT_VERSION, compareVersions, clientSupports } from './registry'
import { planSurface, resolveEntry, variablesOf } from './entry'
import type { SurfaceType, UiView } from '@/api/lap'

/**
 * 五种 Surface 的逐态断言 —— R11 的验收。
 *
 * <h2>为什么是 renderToStaticMarkup 而不是 jsdom + testing-library</h2>
 * 要证明的是"同一份清单在五种容器里被摆成了五种不同的东西", 那是一个**结构**问题。
 * 结构问题用字符串断言比用 DOM 查询更严格 —— `data-surface="PANEL"` 必须真的出现在
 * 输出里, 而不是"某个 class 恰好让它在浏览器里看起来像抽屉"。
 *
 * <h2>这里钉的不是样式, 是契约</h2>
 * 那种"改个 CSS 就红一片"的测试维护起来会被人删掉。所以断言的全是**行为差别**:
 * 谁有遮罩、谁能被关掉、谁会被拒绝渲染、模板里的变量被填成了什么。样式一个都不测 ——
 * 那正是 §69 说的"平台不该定义的东西"。
 */

const APP = 'com.luxera.tictactoe'
const SESSION = 'sess-1234'

/** 五种容器全声明, 且各自 entry 不同 —— 与内置井字棋的清单同形。 */
function allFiveSurfaces(): UiView {
  return {
    type: 'EMBEDDED',
    entry: '/applications/{applicationId}',
    minClientVersion: '1.0.0',
    surfaces: [
      { type: 'FULL_PAGE', entry: '/applications/{applicationId}/sessions/{sessionId}' },
      { type: 'EMBEDDED', entry: '/applications/{applicationId}/sessions/{sessionId}?surface=EMBEDDED' },
      { type: 'MODAL', entry: '/applications/{applicationId}/sessions/{sessionId}?surface=MODAL' },
      { type: 'PANEL', entry: '/applications/{applicationId}/sessions/{sessionId}?surface=PANEL' },
      { type: 'INLINE', entry: '/applications/{applicationId}/sessions/{sessionId}?surface=INLINE' },
    ],
  }
}

const ALL: SurfaceType[] = ['FULL_PAGE', 'EMBEDDED', 'MODAL', 'PANEL', 'INLINE']

function render(ui: UiView, surface: SurfaceType, extra: Record<string, unknown> = {}) {
  return renderToStaticMarkup(
    <SurfaceHost
      applicationId={APP}
      sessionId={SESSION}
      ui={ui}
      surface={surface}
      title="井字棋"
      {...extra}
    />,
  )
}

/** 从渲染结果里把 `data-xxx="..."` 抠出来 —— 断言的就是这些属性。 */
function attr(html: string, name: string): string | null {
  const match = html.match(new RegExp(`data-${name}="([^"]*)"`))
  return match ? match[1] : null
}

describe('SurfaceHost · 五种 Surface 是五种行为', () => {
  it('五种容器各自渲染出自己那一种, 互不串门', () => {
    const seen = ALL.map((surface) => attr(render(allFiveSurfaces(), surface), 'surface'))
    expect(seen).toEqual(ALL)
    // 顺带钉住"五个值就是 §67 列的那五个" —— 多一个少一个都在这条上红。
    expect(new Set(seen).size).toBe(5)
  })

  it('FULL_PAGE 铺满、有标题栏; EMBEDDED 既没有标题栏也没有关闭按钮', () => {
    const full = render(allFiveSurfaces(), 'FULL_PAGE', { onClose: () => {} })
    expect(full).toContain('井字棋')
    expect(full).toContain('data-testid="surface-close"')

    // EMBEDDED 是"嵌在别人页面里的一块": 关掉它不归它管, 所以它不该长出一个关闭按钮。
    // 这一条防的是"五种 Surface 其实是同一个 div 换了 padding"那种退化。
    const embedded = render(allFiveSurfaces(), 'EMBEDDED', { onClose: () => {} })
    expect(embedded).not.toContain('data-testid="surface-close"')
  })

  it('MODAL 有遮罩, PANEL 是贴边的抽屉 —— 两者的外框不同, 而内容相同', () => {
    const modal = render(allFiveSurfaces(), 'MODAL', { onClose: () => {} })
    expect(modal).toContain('bg-black/60')      // 遮罩
    expect(modal).toContain('items-center')     // 居中
    expect(modal).toContain('data-testid="surface-close"')

    const panel = render(allFiveSurfaces(), 'PANEL', { onClose: () => {} })
    expect(panel).not.toContain('bg-black/60')  // 不吃遮罩: 用户可能还在看背后的页面
    expect(panel).toContain('right-0')
    expect(panel).toContain('data-testid="surface-close"')
  })

  it('INLINE 是一行, 而且带一个能升级为整页的入口', () => {
    const inline = render(allFiveSurfaces(), 'INLINE', { onExpand: () => {} })
    expect(inline).toContain('data-testid="surface-inline-expand"')
    expect(inline).not.toContain('min-h-screen')   // 它不是一整页

    // 没给 onExpand 就不该画那个按钮 —— 一个点了没反应的放大镜比没有更糟。
    expect(render(allFiveSurfaces(), 'INLINE')).not.toContain('data-testid="surface-inline-expand"')
  })
})

describe('SurfaceHost · entry 模板', () => {
  it('两个变量都被填进去, 且填的是这一场的那两个值', () => {
    const html = render(allFiveSurfaces(), 'MODAL')
    expect(attr(html, 'entry')).toBe(
      `/applications/${APP}/sessions/${SESSION}?surface=MODAL`,
    )
  })

  it('认不出的变量原样留着 —— 不静默抹成空串', () => {
    const ui: UiView = {
      ...allFiveSurfaces(),
      surfaces: [{ type: 'FULL_PAGE', entry: '/a/{applicationId}/b/{typo}' }],
    }
    // 抹成空串会得到 /a/com.luxera.tictactoe/b/ —— 一条看起来正常的路径, 直到 404 才暴露。
    expect(attr(render(ui, 'FULL_PAGE'), 'entry')).toBe(`/a/${APP}/b/{typo}`)
    expect(variablesOf('/a/{applicationId}/b/{typo}')).toEqual(['applicationId', 'typo'])
  })

  it('还没有会话时 {sessionId} 不会被悄悄填成空', () => {
    expect(resolveEntry('/s/{sessionId}', { applicationId: APP })).toBe('/s/{sessionId}')
    expect(resolveEntry('/s/{sessionId}', { sessionId: SESSION })).toBe(`/s/${SESSION}`)
  })
})

describe('SurfaceHost · 降级与拒绝', () => {
  it('清单里没有这一种 Surface 时退到整页, 并且明说退过档', () => {
    const ui: UiView = {
      ...allFiveSurfaces(),
      surfaces: [{ type: 'FULL_PAGE', entry: '/applications/{applicationId}/sessions/{sessionId}' }],
    }
    const html = render(ui, 'MODAL')
    expect(attr(html, 'surface')).toBe('FULL_PAGE')
    expect(attr(html, 'requested')).toBe('MODAL')
    expect(attr(html, 'fallback')).toBe('true')
    expect(html).toContain('data-testid="surface-fallback-note"')
  })

  it('客户端版本不够就拒绝渲染, 并说清是哪一边旧', () => {
    const ui: UiView = { ...allFiveSurfaces(), minClientVersion: '99.0.0' }
    const html = render(ui, 'FULL_PAGE')
    expect(html).toContain('需要更新的客户端')
    expect(html).toContain('99.0.0')
    expect(html).toContain(CLIENT_VERSION)
    // 拒绝就是拒绝 —— 不能一边说"版本不够"一边照样渲染应用。
    expect(html).not.toContain('data-testid="surface-embedded-app"')
  })

  it('版本比较是数值的: 1.10.0 比 1.9.0 新', () => {
    expect(compareVersions('1.10.0', '1.9.0')).toBe(1)   // 字符串比较会答 -1
    expect(compareVersions('1.0.0', '1.0.0')).toBe(0)
    expect(compareVersions('1.0.0', '1.0.1')).toBe(-1)
    expect(clientSupports(undefined)).toBe(true)
    expect(clientSupports(null)).toBe(true)
    expect(clientSupports(CLIENT_VERSION)).toBe(true)
  })
})

describe('SurfaceHost · 三种 ui.type', () => {
  it('EMBEDDED 但平台没有这个应用的界面实现时, 给一条能走下去的路而不是一句"不支持"', () => {
    // 刻意用一个登记表里绝不会有的 id —— 不能靠"这个测试恰好没 import @/apps"来成立,
    // 那是一个一旦有人加了全局 setup 就会静默失效的前提。
    const html = renderToStaticMarkup(
      <SurfaceHost
        applicationId="com.luxera.no-ui-at-all"
        sessionId={SESSION}
        ui={allFiveSurfaces()}
        surface="FULL_PAGE"
        fallback={<p>动作清单占位</p>}
      />,
    )
    expect(html).toContain('data-testid="surface-unregistered"')
    expect(html).toContain('动作清单占位')
  })

  it('REMOTE 用 iframe 装载, 且 sandbox 收得很紧', () => {
    const ui: UiView = {
      type: 'REMOTE',
      entry: 'https://app.example.com/embed',
      surfaces: [{ type: 'FULL_PAGE', entry: 'https://app.example.com/embed' }],
    }
    const html = render(ui, 'FULL_PAGE')
    expect(html).toContain('data-testid="surface-remote-frame"')
    expect(html).toContain('src="https://app.example.com/embed"')
    // 属性名大小写交给 React 决定(它发的是 referrerPolicy); HTML 属性名本来就不区分大小写,
    // 这里要钉住的是"referrer 策略被设成了 no-referrer"这件事本身。
    expect(html.toLowerCase()).toContain('referrerpolicy="no-referrer"')
    expect(html).toContain('sandbox="allow-scripts allow-forms allow-same-origin allow-popups"')
  })

  it('REMOTE 的 entry 是相对路径时拒绝加载 —— 那是清单错了, 不是页面错了', () => {
    const ui: UiView = {
      type: 'REMOTE',
      entry: '/embed',
      surfaces: [{ type: 'FULL_PAGE', entry: '/embed' }],
    }
    const html = render(ui, 'FULL_PAGE')
    expect(html).toContain('data-testid="surface-remote-invalid"')
    expect(html).not.toContain('<iframe')
  })

  it('NATIVE 在网页版上给深链接并说明 —— 不假装能打开', () => {
    const ui: UiView = {
      type: 'NATIVE',
      entry: 'luxera://app/com.luxera.tictactoe',
      surfaces: [{ type: 'FULL_PAGE', entry: 'luxera://app/com.luxera.tictactoe' }],
    }
    const html = render(ui, 'FULL_PAGE')
    expect(html).toContain('data-testid="surface-native"')
    expect(html).toContain('luxera://app/com.luxera.tictactoe')
    expect(html).not.toContain('<iframe')
  })
})

describe('SurfaceHost · 与后端真正发出来的清单对齐', () => {
  /**
   * 读**后端仓库里那份真的** manifest, 而不是这里手抄一份。
   *
   * 手抄的那份会在某一天和后端不一致, 而那时测试仍然是绿的 —— 它测的是抄本。
   * 这一条要证明的是: 前端能读懂后端**实际发布**的那份清单, 五种 Surface 一条不落。
   */
  // `import.meta.url` 而不是 `__dirname` —— 测试文件是 ESM, 后者在这里并不存在。
  const manifestPath = fileURLToPath(
    new URL(
      '../../../application/src/main/resources/applications/tictactoe/1.0.0/application-manifest.json',
      import.meta.url,
    ),
  )

  it('内置井字棋的 ui 段能驱动五种 Surface 全部渲染出来', () => {
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8')) as {
      ui: { type: string; entry: string; minClientVersion?: string; surfaces: { type: string; entry: string }[] }
    }
    const ui = manifest.ui as unknown as UiView

    expect(ui.surfaces.map((s) => s.type)).toEqual(ALL)

    for (const surface of ALL) {
      const plan = planSurface(ui, surface)
      expect(plan.fallback).toBe(false)                       // 五种都真的声明了
      expect(plan.surface).toBe(surface)
      // 模板里只允许出现平台认得的那两个变量 (§68/§69)
      expect(variablesOf(plan.template).every((v) => ['applicationId', 'sessionId'].includes(v))).toBe(true)

      const html = render(ui, surface)
      expect(attr(html, 'surface')).toBe(surface)
      expect(attr(html, 'fallback')).toBe('false')
    }
  })
})

/**
 * `bleed` —— 小程序宿主用的那一个开关。
 *
 * 它值得一组自己的断言, 因为它是**一个默认值不能变**的字段: 缺省必须与加它之前
 * 逐字节相同(上面那 15 条就是证据), 而打开时必须真的把平台让出去。这两件事都容易被
 * 一次"顺手改改"弄丢, 所以两条都钉住。
 */
describe('SurfaceHost · bleed 让应用拥有整个视口', () => {
  it('缺省(false) 时仍然留白 —— 加这个字段没有改变任何既有调用点', () => {
    const html = render(allFiveSurfaces(), 'FULL_PAGE')
    expect(html).toContain('p-4')
  })

  it('bleed 去掉内边距, 但外框本身一模一样', () => {
    const plain = render(allFiveSurfaces(), 'FULL_PAGE')
    const bleed = render(allFiveSurfaces(), 'FULL_PAGE', { bleed: true })

    // **逐字相等**比"不含 p-4"强: 后者会被内容里碰巧出现的 p-4 骗过去(降级提示就带一个),
    // 而这条要求除那一个 class 之外一个字母都不差 —— 含 min-h-screen 与那四个 data-*。
    expect(bleed).toBe(plain.replace('flex-1 p-4', 'flex-1'))
  })

  it('不传 title 也不传 onClose 时, 平台一条头部都不画', () => {
    // 小程序宿主正是这么用的: title 与 onClose 都不给 → `Frame` 里那一行
    // `{title || onClose ? <Chrome/> : null}` 不成立 → 应用头上不再有平台那一栏。
    // 这是"没有平台栏目"的实现方式, 不是巧合, 所以它被钉在这里。
    const bare = renderToStaticMarkup(
      <SurfaceHost
        applicationId={APP}
        sessionId={SESSION}
        ui={allFiveSurfaces()}
        surface="FULL_PAGE"
        bleed
      />,
    )
    // 框与内容之间直接就是内容 —— 没有夹着一条 Chrome
    expect(bare).toContain('bg-surface "><div class="flex-1"><div')
    expect(bare).not.toContain('data-testid="surface-close"')

    // 反证: 同一个调用只要给了 title, 那一栏就回来了。没有这条, 上面那条可能只是
    // "这个 fixture 恰好画不出标题栏"。
    const titled = renderToStaticMarkup(
      <SurfaceHost
        applicationId={APP}
        sessionId={SESSION}
        ui={allFiveSurfaces()}
        surface="FULL_PAGE"
        title="井字棋"
        bleed
      />,
    )
    expect(titled).toContain('井字棋')
  })

  it('bleed 不会漏到别的容器上', () => {
    // 它是 FULL_PAGE 专属的: 另外四种的外框由各自的交互契约决定, 与内边距无关。
    for (const surface of ALL.filter((s) => s !== 'FULL_PAGE')) {
      expect(attr(render(allFiveSurfaces(), surface, { bleed: true }), 'surface')).toBe(surface)
    }
  })
})
