import { describe, expect, it } from 'vitest'
import type { RouteObject } from 'react-router-dom'
import { fullScreenRoutes, routes, tabRoutes } from './routes'
import { TAB_ITEMS } from './layouts/TabLayout'

/**
 * ## 为什么这里是数据断言, 而不是渲染断言
 *
 * 因为**渲染不了**。`createMemoryRouter(routes, { initialEntries: ['/'] })` +
 * `RouterProvider` 会立刻渲染 `RequireAuth` → 读 `useAuthStore` → `getToken()` →
 * `localStorage.getItem`。vitest 跑在 `environment: 'node'` 下, 那里没有 localStorage,
 * 于是整条链在断言之前就炸了。
 *
 * 给 `client.ts` 的 `getToken()` 加 `typeof localStorage === 'undefined'` 兜底之后它
 * 确实不炸了 —— 但那条兜底是为了让**应用自己能跑**, 不是为了把测试架在它上面:
 * 真要靠渲染来测路由, 就得引 jsdom + testing-library, 而整个前端的测试策略是
 * `renderToStaticMarkup`（见 `SurfaceHost.test.tsx` 文件头那段同样的说明）。
 *
 * 所以这里只断言**这张表本身**。路由表被改坏的方式恰好都是纯数据的:
 * 路径写错、tab 项与路由对不上、全屏页被挂进 tab 分支、`*` 落回一个不存在的路径。
 * 这四种这一份测试全覆盖, 不需要渲染任何一个组件。
 */

/** 从 `<Navigate to="..."/>` 里读出目标路径。 */
function redirectTarget(route: RouteObject): string | undefined {
  const el = route.element as { props?: { to?: unknown } } | undefined
  const to = el?.props?.to
  return typeof to === 'string' ? to : undefined
}

const childPaths = (branch: RouteObject) =>
  (branch.children ?? []).map((c) => c.path).filter((p): p is string => typeof p === 'string')

const findTop = (path: string) => routes.find((r) => r.path === path)

describe('路由表 · 顶层', () => {
  it('登录与注册都在, 且都不是重定向 —— 二期开注册时不用回来改路由', () => {
    expect(findTop('/login')).toBeDefined()
    expect(findTop('/register')).toBeDefined()
    expect(redirectTarget(findTop('/register')!)).toBeUndefined()
  })

  it('兜底落回 /chat, 不是 /', () => {
    // `/` 本身也是一次重定向, 让 `*` 指向它等于套两层, 且改起来容易漏
    expect(redirectTarget(findTop('*')!)).toBe('/chat')
  })

  it('没有重复的顶层 path —— 重复时 react-router 只认第一条, 另一条静静失效', () => {
    const paths = routes.map((r) => r.path).filter(Boolean)
    expect(new Set(paths).size).toBe(paths.length)
  })

  it('每条非重定向路由都真的有 element —— 空壳路由的表现是白屏', () => {
    for (const r of routes) {
      if (r.path === undefined && r.children) continue // 布局分支, 见下一个 describe
      if (redirectTarget(r)) continue
      expect(r.element, `${r.path} 没有 element`).toBeDefined()
    }
  })
})

describe('路由表 · 四 tab（布局 A）', () => {
  // 顺序不在这里断言 —— tab 的实际顺序由 TabBar 的 items 决定, 不由此表的书写顺序决定。
  // 断言顺序等于把一处排版偏好伪装成一条契约。
  it('tab 路由正好是 /chat /contacts /discover /me（外加 / 的重定向）', () => {
    const paths = childPaths(tabRoutes)
    expect([...paths].sort()).toEqual(['/', '/chat', '/contacts', '/discover', '/me'].sort())
  })

  it('tab 项与 tab 路由一一对应 —— 两边任一多出来一项都是真 bug', () => {
    // 少一边的症状: 界面上有个点进去是白屏的 tab, 或页面存在却没有任何入口能到
    const routePaths = childPaths(tabRoutes).filter((p) => p !== '/')
    expect(TAB_ITEMS.map((i) => i.to).sort()).toEqual([...routePaths].sort())
  })

  it('「聊天」tab 是精确匹配 —— 否则 /chat/:id 时它会错误地保持高亮', () => {
    const chat = TAB_ITEMS.find((i) => i.to === '/chat')
    expect(chat?.end).toBe(true)
  })

  it('/ 重定向到 /chat', () => {
    const root = (tabRoutes.children ?? []).find((c) => c.path === '/')
    expect(redirectTarget(root!)).toBe('/chat')
  })
})

describe('路由表 · 全屏（布局 B）', () => {
  it('聊天室不在 tab 分支里 —— 挂进去底部会压着一条 tab bar, 输入框被顶到屏幕外', () => {
    expect(childPaths(tabRoutes)).not.toContain('/companions/:id')
  })

  it('聊天室此刻仍在 /companions/:id —— 第 5 步随 ChatRoom 一起搬到 /chat/:conversationId', () => {
    // 这里是**当前事实**, 不是终态。终态是 `/chat/:conversationId`:
    // 今天 activeConvId 是 Chat.tsx 里的局部 state, URL 里只有伴侣 id,
    // 于是刷新页面会跳到第一个会话而不是你在看的那个。
    // 提前把这条路径挂上去做不到 —— `Chat` 读的是 `useParams<{id}>`(一个 companionId),
    // 而 `/chat/:conversationId` 里那一段是会话 id, 两者不是一回事。
    // 第 5 步 `ChatRoom` 落地时, 这个断言与那边一起翻。
    expect(childPaths(fullScreenRoutes)).toContain('/companions/:id')
  })

  it('分享票那三条路都在 —— §16 的单入口链路上有旧链接在外面', () => {
    const paths = childPaths(fullScreenRoutes)
    for (const p of [
      '/applications/:applicationId',
      '/applications/:applicationId/sessions/:sessionId',
      '/sessions/:sessionId',
      '/join/:token',
    ]) {
      expect(paths, `${p} 丢了`).toContain(p)
    }
  })
})

describe('路由表 · 老路径的落点', () => {
  it.each([
    ['/companions', '/contacts'],
    ['/applications', '/discover'],
  ])('%s 重定向到 %s', (from, to) => {
    expect(redirectTarget(findTop(from)!)).toBe(to)
  })

  it('/companions/:id 仍然可达 —— 老链接不能断, 第 5 步才换', () => {
    expect(childPaths(fullScreenRoutes)).toContain('/companions/:id')
  })
})
