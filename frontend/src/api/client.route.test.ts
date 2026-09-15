import { describe, expect, it } from 'vitest'
import { route } from './client'

/**
 * G5 —— 双服务路由的归属判定。
 *
 * G1 拆分后伴侣域随 digital-human 迁到 8091, 会话/消息/事件流仍留在 8081;
 * 而两者的路径共享 `/api/companions` 这个前缀, 交错在 `/{id}` 段之下。这个测试
 * 要钉死的就是"哪个段属于哪个服务"——判断错了, 前端会把记忆/关系的请求打到
 * 聊天平台去(404), 或把会话请求打到数字人平台去(语义错乱)。
 *
 * 判据(与 src/api/client.ts 的 route() 注释一致):
 *   - chat 保留段: conversations / messages / events / threads / applications → 8081
 *   - 其余伴侣域段(含 /api/companions 本身与 /{id} 本身)→ 8091(/agent 前缀)
 */
describe('route() 双服务分流', () => {
  describe('8081 侧(聊天平台, 不加 /agent 前缀)', () => {
    it.each([
      '/api/auth/login',
      '/api/auth/me',
      '/api/v1/applications',
      '/api/v1/actions:execute',
      '/api/companions/c1/conversations',
      '/api/companions/c1/conversations/conv-1/messages',
      '/api/companions/c1/conversations/conv-1/participants',
      '/api/companions/c1/events',
      '/api/companions/c1/threads',
      '/api/companions/c1/threads/active',
      '/api/companions/c1/conversations/conv-1/applications',
    ])('原样返回: %s', (url) => {
      expect(route(url)).toBe(url)
    })
  })

  describe('8091 侧(仿真 Agent 平台, 加 /agent 前缀)', () => {
    it.each([
      ['/api/companions', '/agent/api/companions'],
      ['/api/companions/compile', '/agent/api/companions/compile'],
      ['/api/companions/preview', '/agent/api/companions/preview'],
      ['/api/companions/c1', '/agent/api/companions/c1'],
      ['/api/companions/c1/memories', '/agent/api/companions/c1/memories'],
      ['/api/companions/c1/memories/search?q=x', '/agent/api/companions/c1/memories/search?q=x'],
      ['/api/companions/c1/relationship', '/agent/api/companions/c1/relationship'],
      ['/api/companions/c1/relationship/narrative', '/agent/api/companions/c1/relationship/narrative'],
      ['/api/companions/c1/relationship/threads', '/agent/api/companions/c1/relationship/threads'],
      ['/api/companions/c1/life', '/agent/api/companions/c1/life'],
      ['/api/companions/c1/life-events', '/agent/api/companions/c1/life-events'],
      ['/api/companions/c1/self', '/agent/api/companions/c1/self'],
      ['/api/companions/c1/reminders', '/agent/api/companions/c1/reminders'],
      ['/api/companions/c1/reminders/r1/done', '/agent/api/companions/c1/reminders/r1/done'],
      ['/api/companions/c1/notifications', '/agent/api/companions/c1/notifications'],
      ['/api/companions/c1/notifications/unread-count', '/agent/api/companions/c1/notifications/unread-count'],
      ['/api/companions/c1/user-model/facts', '/agent/api/companions/c1/user-model/facts'],
      ['/api/companions/c1/state', '/agent/api/companions/c1/state'],
      ['/api/companions/c1/reflections', '/agent/api/companions/c1/reflections'],
      ['/api/companions/c1/persona', '/agent/api/companions/c1/persona'],
      ['/api/companions/c1/persona/versions', '/agent/api/companions/c1/persona/versions'],
    ])('%s → %s', (url, expected) => {
      expect(route(url)).toBe(expected)
    })
  })

  describe('边界用例(最容易被一刀切误判)', () => {
    it('/api/companions/{id} 详情不得误判成 /conversations', () => {
      expect(route('/api/companions/c1')).toBe('/agent/api/companions/c1')
    })

    it('/api/companions/{id}/conversations 之后无论多深都属于 8081', () => {
      // 会话子路径(即便 segment 莫名重叠)必须留在聊天平台
      expect(route('/api/companions/c1/conversations/conv-1/messages')).toBe(
        '/api/companions/c1/conversations/conv-1/messages',
      )
    })

    it('带 query / 尾斜杠的根路径正确归类 8091', () => {
      expect(route('/api/companions/')).toBe('/agent/api/companions/')
      expect(route('/api/companions?filter=x')).toBe('/agent/api/companions?filter=x')
    })

    it('非 /api/companions 前缀不受影响(哪怕包含 memories 字样)', () => {
      expect(route('/api/v1/memories')).toBe('/api/v1/memories')
    })
  })
})