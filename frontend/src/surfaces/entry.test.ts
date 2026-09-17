import { describe, expect, it } from 'vitest'
import { isAbsoluteHttpUrl, isCrossOrigin, planSurface, resolveEntry } from './entry'
import type { UiView } from '@/api/lap'

/**
 * 入口地址这一层的判据。
 *
 * <h2>为什么 `isCrossOrigin` 值得一个自己的测试文件</h2>
 *
 * 它是**唯一**一条挡在"第三方应用直接读宿主页面"前面的判断, 而它错了不会有任何症状:
 * 应用照常显示、照常能用, 只是它同时也拿到了整个宿主。同源与不同源在界面上长得一模一样,
 * 差别只在于有没有人能从 iframe 里 `window.parent.document` 够到会话。
 *
 * 所以这里钉的是**边界**, 不是正路: 默认端口的归一化、大小写、以及
 * "看起来像我们的域名其实不是"那一种(`chat.luxera.top.evil.com`)。
 */

const HOST = 'https://chat.luxera.top'

describe('isCrossOrigin', () => {
  it('同源一律拒绝', () => {
    expect(isCrossOrigin('https://chat.luxera.top/apps/demo/index.html', HOST)).toBe(false)
    expect(isCrossOrigin('https://chat.luxera.top/', HOST)).toBe(false)
  })

  it('默认端口要归一化 —— 写不写 :443 是同一个源', () => {
    // 这一条是**最容易漏**的: 字面量比较会认为 `:443` 不同, 于是把一个同源应用放进去。
    // `URL` 会自己把它规范化掉, 这也是这里用 `new URL` 而不是拆字符串的原因。
    expect(isCrossOrigin('https://chat.luxera.top:443/apps/x.html', HOST)).toBe(false)
    expect(isCrossOrigin('https://chat.luxera.top:8443/apps/x.html', HOST)).toBe(true)
  })

  it('大小写不影响判定', () => {
    // 域名与协议都是大小写不敏感的, 而 `entry` 是人手写在清单里的。
    expect(isCrossOrigin('HTTPS://CHAT.LUXERA.TOP/apps/x.html', HOST)).toBe(false)
  })

  it('看着像但不是 —— 子域名、后缀伪装都算不同源', () => {
    // `chat.luxera.top.evil.com` 的前缀是骗人看的; 浏览器按**最后一个**点分标签判归属,
    // 所以它是 evil.com 的。这里跟着浏览器的判据走, 不自己解析。
    expect(isCrossOrigin('https://chat.luxera.top.evil.com/x', HOST)).toBe(true)
    expect(isCrossOrigin('https://evil.com/chat.luxera.top', HOST)).toBe(true)
    expect(isCrossOrigin('https://apps.chat.luxera.top/x', HOST)).toBe(true)
  })

  it('协议或端口不同就是不同源', () => {
    expect(isCrossOrigin('http://chat.luxera.top/x', HOST)).toBe(true)
    expect(isCrossOrigin('https://127.0.0.1:8095/ui/s-1', HOST)).toBe(true)
  })

  it('拿不到宿主的 origin 时不判 —— 静态渲染与测试走这一支', () => {
    // `SurfaceHost` 的 15 条断言全部是 `renderToStaticMarkup`, 那时没有 `window`。
    // 在那种环境下凭空拒绝渲染, 会让那些断言保护不到这条分支 —— 而它们保护的正是它。
    expect(isCrossOrigin('https://chat.luxera.top/apps/x.html', '')).toBe(true)
    expect(isCrossOrigin('https://anything.example/x', '')).toBe(true)
  })

  it('解析不出来的地址交给 isAbsoluteHttpUrl 去报错, 这里不重复判', () => {
    // 两条判断各管一件事: 这条管"在不在同一个源", 那条管"是不是一条绝对 http(s) 地址"。
    // 让这条也去管格式, 结果是一条坏 entry 收到两句互相矛盾的话。
    expect(isCrossOrigin('/apps/x.html', HOST)).toBe(true)
    expect(isAbsoluteHttpUrl('/apps/x.html')).toBe(false)
  })
})

describe('resolveEntry', () => {
  it('只填两个变量, 第三个原样留着', () => {
    // 第三条不是疏忽: 它是作者与平台之间的分歧, 抹平成空白只会让分歧更晚被发现。
    expect(
      resolveEntry('https://apps.example/{applicationId}/{sessionId}/{version}', {
        applicationId: 'com.example.a',
        sessionId: 's-1',
      }),
    ).toBe('https://apps.example/com.example.a/s-1/{version}')
  })

  it('没有会话时 sessionId 不被填进去', () => {
    // 应用详情页里预览一个应用就是这种情况 —— 那时没有"哪一场"可言。
    expect(resolveEntry('https://apps.example/{sessionId}', { applicationId: 'a' })).toBe(
      'https://apps.example/{sessionId}',
    )
  })
})

describe('planSurface', () => {
  const ui = (surfaces: UiView['surfaces']): UiView => ({
    type: 'REMOTE',
    entry: 'https://apps.example/plain',
    surfaces,
  })

  it('声明了就用声明的那一份入口', () => {
    const plan = planSurface(ui([{ type: 'PANEL', entry: 'https://apps.example/panel' }]), 'PANEL')
    expect(plan).toEqual({
      requested: 'PANEL',
      surface: 'PANEL',
      template: 'https://apps.example/panel',
      fallback: false,
    })
  })

  it('没声明就退到整页, 而且连摆法一起退', () => {
    // 把一份按整页设计的界面硬塞进弹窗, 得到的是一个尺寸不对、还可能自带返回按钮的浮层。
    const plan = planSurface(ui([{ type: 'FULL_PAGE', entry: 'https://apps.example/full' }]), 'MODAL')
    expect(plan.surface).toBe('FULL_PAGE')
    expect(plan.requested).toBe('MODAL')
    expect(plan.template).toBe('https://apps.example/full')
    expect(plan.fallback).toBe(true)
  })

  it('一条 surface 都没声明时, 用清单顶层的 entry 且**不换摆法**', () => {
    // 换摆法是因为"有一份为整页设计的界面, 硬塞进弹窗会难看"。连整页都没声明时没有
    // 任何东西可以换过去, 于是留在用户点进来的那种摆法里, 用顶层 entry 撑住。
    const plan = planSurface(ui([]), 'MODAL')
    expect(plan.template).toBe('https://apps.example/plain')
    expect(plan.surface).toBe('MODAL')
    // `fallback` 仍然是 true: 应用确实没有为 MODAL 声明入口, 界面上那句话因此照说。
    // 那句话在**这一种**情形下读起来有点绕("没有为 MODAL 提供入口, 已按 MODAL 打开"),
    // 但它说的是事实 —— 写测试的人在这里要如实记下, 而不是把行为改成读起来顺的。
    expect(plan.fallback).toBe(true)
  })
})
