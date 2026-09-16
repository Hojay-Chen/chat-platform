import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import ApplicationCardBubble from './ApplicationCardBubble'
import { cardOf, invitationOf } from '@/api/chatApplications'
import type { Message } from '@/types'

/**
 * R12 §66 的验收: <b>应用卡片就是一条消息</b> —— 而这条消息的每一种降级路径都要能站住。
 *
 * <h2>为什么是 renderToStaticMarkup</h2>
 * 与 `SurfaceHost.test.tsx` 同一个理由: 要钉的是"这条消息被摆成了什么", 而不是"点下去
 * 发生了什么"。样式一个都不测 —— 那会变成一改 CSS 就红一片的测试, 然后被人删掉。
 *
 * <h2>真正在下判断的是降级, 不是正路</h2>
 * 正路(metadata 齐全 → 画一张卡片)是一行代码。会出事的是另外三条: metadata 少了一个键、
 * 消息来自一个认不出 `messageKind` 的旧客户端、以及 `joinUrl` 是相对路径。这三条各有断言。
 */

const COMPANION = 'c-1'
const CONVERSATION = 'v-1'

function message(overrides: Partial<Message>): Message {
  return {
    id: 'm-1',
    conversationId: CONVERSATION,
    senderType: 'system',
    content: '「纸飞机」已在这段对话里开启',
    createdAt: '2026-09-12T10:00:00',
    ...overrides,
  }
}

function render(m: Message): string {
  return renderToStaticMarkup(
    <MemoryRouter>
      <ApplicationCardBubble message={m} companionId={COMPANION} conversationId={CONVERSATION} />
    </MemoryRouter>,
  )
}

describe('应用卡片消息', () => {
  it('卡片的内容来自 metadata, 而不是 content', () => {
    const html = render(
      message({
        messageKind: 'APPLICATION_CARD',
        content: '这段文字不该出现在卡片上',
        metadata: {
          applicationId: 'com.example.paper-plane',
          sessionId: 'sess-9',
          name: '纸飞机',
          description: '折一只会飞的纸飞机',
          role: 'OWNER',
          status: 'ACTIVE',
        },
      }),
    )
    expect(html).toContain('纸飞机')
    expect(html).toContain('折一只会飞的纸飞机')
    // 名字、描述都取自 metadata —— content 只在 metadata 缺东西时兜底。
    expect(html).not.toContain('这段文字不该出现在卡片上')
  })

  it('metadata 里没有 sessionId 时退回显示 content', () => {
    // 旧消息、或者某个客户端手工插的行。一条画不出来的卡片必须退化成一个能读的气泡,
    // 而不是一个空白框 —— 这正是平台要求 content 写成一句人话的理由。
    const html = render(
      message({
        messageKind: 'APPLICATION_CARD',
        content: '「纸飞机」已在这段对话里开启',
        metadata: { applicationId: 'com.example.paper-plane' },
      }),
    )
    expect(html).toContain('「纸飞机」已在这段对话里开启')
  })

  it('邀请消息把 joinUrl 渲染成一个点得开的链接', () => {
    // 平台给的是相对路径, <a href> 就原样用它 —— 浏览器按当前 origin 解析, 右键"复制链接
    // 地址"拿到的也是完整 URL。这个组件因此不在渲染阶段读 window, 也就不会在某天被搬去
    // 服务端渲染时炸掉。
    const html = render(
      message({
        messageKind: 'APPLICATION_INVITATION',
        content: '邀请你加入「纸飞机」',
        metadata: { invitationId: 'inv-1', sessionId: 'sess-9', joinUrl: '/join/tok-abc', role: 'MEMBER' },
      }),
    )
    expect(html).toContain('href="/join/tok-abc"')
    expect(html).toContain('邀请你加入「纸飞机」')
    expect(invitationOf({ joinUrl: '/join/tok-abc' }).joinUrl).toBe('/join/tok-abc')
  })

  it('邀请消息丢了 joinUrl 时退回 content', () => {
    const html = render(
      message({
        messageKind: 'APPLICATION_INVITATION',
        content: '邀请你加入「纸飞机」',
        metadata: { invitationId: 'inv-1' },
      }),
    )
    expect(html).toContain('邀请你加入「纸飞机」')
    expect(html).not.toContain('<a ')
  })

  it('认不出的 messageKind 什么也不画 —— 由调用方退回普通气泡', () => {
    // 返回 null 而不是"画一个空卡片": 这条消息在一张会画卡片的表里没有位置,
    // 但它在消息流里仍然有位置(Chat.tsx 的 else 分支)。
    const html = render(message({ messageKind: 'NORMAL', content: '普通消息' }))
    expect(html).toBe('')
  })
})

describe('聊天侧的前端也不认识任何具体应用', () => {
  /**
   * 与后端 `ChatApplicationPortTest` 的那条源码扫描同形 —— 只是换了一侧。
   *
   * 后端守的是"聊天平台不在代码里认应用"; 前端守的是同一句话的另一半: 应用的名字与图标
   * 全都来自 `metadata` / `GET .../applications` 的响应, 而不是页面里的一张硬编码表。
   * 一张硬编码表会让"新应用接进来"从一个零改动的动作变成一次前端发版。
   */
  // 第 5 步新增: 这两个文件也在"画消息"的路径上, 而且 `ChatRoomPanel` 会列出
  // 「可以开一个」的清单。清单里那些名字从 `GET .../applications` 的响应来,
  // 一旦有人为了让某个应用好看起来在这里写死一个名字, 这条断言就会红。
  /**
   * 扫哪些文件。
   *
   * **这张表要跟着 IA 长大。** 它的作用是"聊天前端里不许出现任何具体应用的名字",
   * 而这条约束只在被扫到的文件上生效 —— 新写了一屏消息界面却忘了加进来, 约束就静默
   * 失效了, 而失效的表现是"什么也没发生", 没人会注意到。
   *
   * 所以第 6 步把通讯录与 Agent 资料页那一批全加了进来: 它们渲染 Agent 域的数据,
   * 是"顺手写一句 `if (id === 'com.luxera.gomoku')`"最可能发生的地方。
   */
  const FILES = [
    'src/components/ApplicationCardBubble.tsx',
    'src/api/chatApplications.ts',
    'src/api/agent.ts',
    'src/pages/chat/ChatRoom.tsx',
    'src/pages/chat/ChatRoomPanel.tsx',
    'src/pages/contacts/Contacts.tsx',
    'src/pages/contacts/AgentProfile.tsx',
    'src/pages/contacts/AgentSettings.tsx',
    'src/pages/CompanionCreate.tsx',
    'src/components/agent/PanelState.tsx',
    'src/components/agent/RecentPanel.tsx',
    'src/components/agent/MemoriesPanel.tsx',
    'src/components/agent/UserModelPanel.tsx',
    'src/components/agent/RelationshipPanel.tsx',
  ]

  const FORBIDDEN = [
    'com.luxera.tictactoe',
    'com.luxera.gomoku',
    'com.luxera.reminder',
    '井字棋',
    '五子棋',
    'game.make_move',
    'gomoku.',
    'reminder.',
  ]

  it.each(FILES)('%s 里没有任何一个具体应用的名字', (relative) => {
    const source = readFileSync(fileURLToPath(new URL('../../' + relative, import.meta.url)), 'utf8')
    // 注释不算 —— 这条规则管的是代码认识什么, 一段说"这里不认井字棋"的注释是好的。
    const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '')
    for (const word of FORBIDDEN) {
      expect(code, `${relative} 的代码里出现了具体应用的词汇: ${word}`).not.toContain(word)
    }
  })
})

describe('metadata 读取器', () => {
  it('把 JSON 里的乱七八糟读成有类型的对象', () => {
    // metadata 来自数据库里的一列 JSON, 它可能是任何东西 —— 包括 null 和一个数字。
    const card = cardOf(null)
    expect(card.applicationId).toBeNull()
    expect(card.sessionId).toBeNull()

    const odd = cardOf({ applicationId: 42, name: '', sessionId: 'sess-1' })
    expect(odd.applicationId).toBeNull() // 数字不是 id
    expect(odd.name).toBeNull() // 空串不是名字
    expect(odd.sessionId).toBe('sess-1')
  })

  it('maxUses 只认数字', () => {
    expect(invitationOf({ maxUses: 3 }).maxUses).toBe(3)
    expect(invitationOf({ maxUses: '3' }).maxUses).toBeNull()
    expect(invitationOf({}).maxUses).toBeNull()
  })
})
