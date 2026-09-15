import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import * as client from './client'

/**
 * G8 —— 前端不得再做服务分流。
 *
 * 这个文件取代了 G5 的 `client.route.test.ts`(36 条断言, 全部在钉"路径段 → 服务"的映射表)。
 * 那张表已经被删除, 因为它的判据是错的 —— 它按**路径段**猜端点归属, 而 `conversations`
 * 段两端都有端点:
 *
 *   GET  /api/companions/{id}/conversations            → 8081(会话列表)
 *   POST /api/companions/{id}/conversations/first      → 8091(开/复用会话, 回 ConversationView)
 *   POST /api/companions/{id}/conversations/{cid}/chat → 8091(SSE 流式聊天)
 *
 * 段规则把整段判给 8081, 于是流式聊天入口打到了没有该端点的服务上, 404。
 *
 * 值得记住的是**那 36 条断言为什么没拦住**: 它们覆盖了 `conversations/{cid}/messages`
 * (确实属于 8081), 却从没覆盖 `conversations/first` 和 `conversations/{cid}/chat`
 * (属于 8091)。测试把规则本身当成事实来断言, 而没有去问"这条规则在所有真实 URL 上都成立吗"。
 * 覆盖率高不等于判据正确 —— 一个错的判据可以被一千条断言钉得死死的。
 *
 * 现在的分工: 浏览器只跟聊天平台一个域名说话, 伴侣域由 8081 在服务端转发(见后端
 * `CompanionDomainProxyController`, 那里按 Spring 的 HandlerMapping 判归属, 依据是
 * "8081 到底实现了什么", 不是路径段)。前端这一层因此必须保持**零改写**。
 */
describe('G8: 前端零分流', () => {
  it('client 不再导出 route()', () => {
    expect('route' in client).toBe(false)
  })

  it('源码里不存在 /agent 前缀改写', () => {
    const offenders: string[] = []
    const walk = (dir: string) => {
      for (const name of readdirSync(dir)) {
        const p = join(dir, name)
        if (statSync(p).isDirectory()) {
          walk(p)
        } else if (/\.(ts|tsx)$/.test(name) && !/\.test\.(ts|tsx)$/.test(name)) {
          // 跳过测试文件: 它们**必须**能写出那个被禁的字面量才能断言它不存在
          // (本文件自己就写了 '/agent')。扫的是产品代码。
          const src = readFileSync(p, 'utf8')
          // 只找代码里的字面量, 注释里提到 /agent(解释历史)不算
          const stripped = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
          if (/['"`]\/agent/.test(stripped)) offenders.push(p)
        }
      }
    }
    walk(join(import.meta.dirname, '..'))
    expect(offenders).toEqual([])
  })

  it('前端 URL 面就是它字面上的意思(伴侣域与聊天域同源)', () => {
    // 这些路径现在全部打向 8081, 由后端决定本地处理还是转给 8091 ——
    // 前端不需要知道、也不应该知道哪条属于谁。
    const surface = [
      '/api/auth/login',
      '/api/v1/applications',
      '/api/companions',
      '/api/companions/c1',
      '/api/companions/c1/memories',
      '/api/companions/c1/relationship',
      '/api/companions/c1/state',
      '/api/companions/c1/conversations',
      '/api/companions/c1/conversations/first',
      '/api/companions/c1/conversations/conv-1/chat',
      '/api/companions/c1/conversations/conv-1/messages',
      '/api/companions/c1/events',
    ]
    // 断言的是"没有前缀", 而不是每个 URL 映射到哪个服务 —— 后者是后端的事,
    // 在这里断言等于把已被证伪的判据换个地方再写一遍。
    for (const url of surface) {
      expect(url.startsWith('/agent')).toBe(false)
    }
  })
})
