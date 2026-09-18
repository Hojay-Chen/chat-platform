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
    expect(childPaths(tabRoutes)).not.toContain('/chat/:conversationId')
    expect(childPaths(tabRoutes)).not.toContain('/companions/:id')
  })

  it('聊天室是 /chat/:conversationId, 且它在全屏那一支', () => {
    // 这一条钉住的是"一段对话由会话 id 寻址"。写成 `/chat/:id`(伴侣 id) 也能跑,
    // 但刷新页面就会跳到第一个会话 —— 一个伴侣可以有多段对话, 那不是同一个东西。
    expect(childPaths(fullScreenRoutes)).toContain('/chat/:conversationId')
  })

  it('/chat 下没有静态子路由 —— 有的话 react-router 会把那一段当成 conversationId', () => {
    // `/chat/new` 这种写法在 react-router 7 里不会报错: 它优先匹配静态段,
    // 但一旦有人把路径写成 `/chat/:conversationId/xxx` 或加了 `/chat/new` 又改回来,
    // 症状是"点发起群聊进入了某个人的聊天室, 而且那一段 id 是 `new`"。
    const under = childPaths(fullScreenRoutes).filter((p) => p.startsWith('/chat/'))
    expect(under).toEqual(['/chat/:conversationId'])
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

describe('路由表 · 通讯录的下一层', () => {
  it('三条都在全屏那一支 —— 它们是从通讯录点进去的路径, 底部不该有 tab bar', () => {
    const paths = childPaths(fullScreenRoutes)
    for (const p of [
      '/contacts/new',
      '/contacts/agent/:companionId',
      '/contacts/agent/:companionId/settings',
    ]) {
      expect(paths, `${p} 丢了`).toContain(p)
    }
  })

  it('资料页与设置页都是 `:companionId`, 不是 `:id` —— 参数名变了要一起改', () => {
    // 这一条不值钱地钉住一个命名: 路由段叫 `:companionId` 而组件里 `useParams` 取 `id`,
    // 症状是 `companionId` 恒为 undefined —— 页面能打开, 但每个请求都打到
    // `/api/companions/undefined/...`。类型系统看不见它, 因为 `useParams` 返回的是
    // `Record<string, string | undefined>`。
    const paths = childPaths(fullScreenRoutes)
    expect(paths.filter((p) => p.startsWith('/contacts/') && p !== '/contacts/new')).toEqual([
      '/contacts/agent/:companionId',
      '/contacts/agent/:companionId/settings',
    ])
  })

  it('设置页在资料页**下面** —— 它要靠资料页那个齿轮进去, 不是平级', () => {
    const settings = childPaths(fullScreenRoutes).find((p) => p.endsWith('/settings'))!
    expect(settings.startsWith('/contacts/agent/:companionId')).toBe(true)
  })
})

describe('路由表 · 「我」的下一层', () => {
  it('提醒、通知、账号ID 在全屏那一支 —— 它们不是"一栏", 进去之后底部不该还有 tab bar', () => {
    const paths = childPaths(fullScreenRoutes)
    for (const p of ['/me/reminders', '/me/notifications', '/me/handle']) {
      expect(paths, `${p} 丢了`).toContain(p)
    }
    // 反过来也要断言: 挂进 tab 分支的话 tab bar 会一直在, 而且四项会变成六项 ——
    // 上面那条「tab 项与 tab 路由一一对应」会红, 但那一条红的原因不会指向这里
    expect(childPaths(tabRoutes)).not.toContain('/me/reminders')
  })

  /**
   * 会话免打扰的总览。
   *
   * 它与 `/me/notifications` **不是同一件事**, 尽管两个名字里都有"打扰"两个字:
   * 通知那一页说的是"她主动找过你的时刻"(她发给你的历史), 这一页说的是"你不想被
   * 这一段打扰"(你设的开关)。混成一条路径的症状是其中一件事从此没有入口 ——
   * 而两件事都各自有一个静默的故障形态, 都必须能被单独找到。
   */
  it('会话免打扰的总览在 /me/ 之下, 且**不是**通知那一页', () => {
    const paths = childPaths(fullScreenRoutes)
    expect(paths).toContain('/me/conversations')
    expect(childPaths(tabRoutes)).not.toContain('/me/conversations')
    // 它必须在 /me/ 之下: 写成 `/conversations` 也能跑, 症状是这一页没有入口能到,
    // 而 `/chat` 那一支下面又不能挂静态子路由(见上一条), 所以两条路都不通
    expect(paths.filter((p) => p.startsWith('/conversations'))).toEqual([])
  })

  it('/me 本身仍然是 tab, 不是重定向 —— 「我」是一栏, 不是一条记录', () => {
    const me = (tabRoutes.children ?? []).find((c) => c.path === '/me')
    expect(me).toBeDefined()
    expect(redirectTarget(me!)).toBeUndefined()
  })

  it('两条路径都在 /me/ 之下 —— 不然它们就成了顶层的第五条路, 而顶层没有第五个 tab', () => {
    // 写成 `/reminders` 也能跑, 症状是"这一页没有入口能到" —— 除了地址栏
    const under = childPaths(fullScreenRoutes).filter(
      (p) => p.includes('reminder') || p.includes('notification'),
    )
    expect(under.sort()).toEqual(['/me/notifications', '/me/reminders'])
  })

  /**
   * 改自己的账号ID —— 路径里**只有一个 `me`, 没有任何参数**。
   *
   * 这一条断言的理由不是"整齐", 是安全: 后端端点是 `/api/persons/me/handle`, userId 从
   * 已认证身份里取, 调用方**没有机会指定改谁** —— 越权在那个形状下无法被表达。
   * 一旦前端路径变成 `/me/handle/:personId`, 界面就会开始暗示"可以改别人", 而那个暗示
   * 迟早会诱使后端跟着加一个 `{id}` 端点, 那正是这个设计刻意消除的入口。
   *
   * 所以这里断言的是"带 handle 的路径**恰好只有**这一条", 而不是"它存在" ——
   * 前者能拦住后来者顺手加一个平行入口。
   */
  it('账号ID 页在 /me/ 之下, 且路径里不含参数 —— 它没有"改谁"这个维度', () => {
    const paths = childPaths(fullScreenRoutes)
    expect(paths.filter((p) => p.includes('handle'))).toEqual(['/me/handle'])
    // 与提醒/通知同理: 挂进 tab 分支会让底部一直压着 tab bar, 且四项变五项
    expect(childPaths(tabRoutes)).not.toContain('/me/handle')
  })
})

describe('路由表 · 老路径的落点', () => {
  it.each([
    ['/companions', '/contacts'],
    ['/companions/new', '/contacts/new'],
    ['/applications', '/discover'],
  ])('%s 重定向到 %s', (from, to) => {
    expect(redirectTarget(findTop(from)!)).toBe(to)
  })

  it.each([
    ['/companions/:id', '/contacts/agent/:id'],
    ['/companions/:id/settings', '/contacts/agent/:id/settings'],
  ])('%s 带着参数重定向到 %s', (from, to) => {
    // 第 5 步这一条还是**页面**(老 `Chat.tsx`), 因为新建 Agent 的流程要经它调
    // `conversations/first` 建会话并触发问候语。第 6 步资料页把「发消息」这件事接过去
    // 之后, 老页面连同 `Chat.tsx` / `Settings.tsx` 一起删除, 这里只剩一条重定向。
    //
    // 目标里必须**保留参数**: 写成 `/contacts/agent/` 是个能编译、能跑、把每个人都送到
    // 同一个空白资料页的 bug。所以断言的是模板串本身, 而不是某个具体的人。
    expect(redirectTarget(findTop(from)!)).toBe(to)
  })
})
