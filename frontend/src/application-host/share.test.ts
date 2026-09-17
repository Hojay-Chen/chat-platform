import { describe, expect, it } from 'vitest'
import {
  MAX_NOTE,
  coverOf,
  describeShareFailure,
  filterRecipients,
  isDisplayableCover,
  normalizeNote,
  normalizeShare,
  noteRemaining,
} from './share'
import { ChatApplicationError } from '@/api/chatApplications'
import type { ConversationSummary } from '@/api/conversations'

/**
 * 分享这条链上**不含渲染的那一半**。
 *
 * <p>它们全都是"错了不会报错"的那一类: 附言判宽了, 用户能往别人的消息流里灌一万字;
 * 候选人筛错了, 搜"Maya"搜不到 Maya, 而用户只会以为"这个平台搜不了"; 封面判宽了,
 * 收件人的客户端会去拉一个第三方地址 —— 那是把**收到邀请的人**的 IP 与阅读时间送给应用
 * 作者, 而这件事在他那一侧完全不可见。
 */

describe('normalizeNote —— 与服务端逐字一致的形状', () => {
  it('没写就是空串, 不是 undefined', () => {
    // 输入框下面那个计数器要能直接对 null 求长度。
    for (const empty of [null, undefined, '', '   ', '\n\n\t ']) {
      expect(normalizeNote(empty)).toBe('')
    }
  })

  it('首尾空白收掉', () => {
    expect(normalizeNote('  来玩吗?  ')).toBe('来玩吗?')
  })

  it('横向空白收成一整类 —— 不换行空格与全角空格同样收', () => {
    // 这三兄弟在气泡里长得与普通空格一模一样, 所以只收 ASCII 空格等于没做这件事。
    // 与服务端 ConversationApplicationService.sanitizeNote 是同一个字符集。
    expect(normalizeNote('来\t\t玩  吗')).toBe('来 玩 吗')
    expect(normalizeNote('来\u00a0\u00a0玩 吗')).toBe('来 玩 吗')
    expect(normalizeNote('来\u3000玩 吗')).toBe('来 玩 吗')
    // 收完是普通空格, 不是删掉 —— 删掉会把两个词粘成一个。
    expect(normalizeNote('a\u3000b')).toBe('a b')
  })

  it('单个换行保留, 连续空行压成一个', () => {
    // 换行是**正常输入** —— 用户会在附言里分行。压掉它等于替用户改写他说的话。
    expect(normalizeNote('第一行\n第二行')).toBe('第一行\n第二行')
    expect(normalizeNote('第一行\n\n\n\n第二行')).toBe('第一行\n\n第二行')
  })

  it('控制字符删掉 —— 它们只是会让别人排查时看不懂', () => {
    // 写成 \u 转义而不是字面量: 字面量在这个文件里是看不见的, 而"看不见"恰恰就是
    // 这段代码在删的东西。U+0000 NUL / U+0007 BEL / U+001F US / U+007F DEL。
    expect(normalizeNote('a\u0000b\u0007c')).toBe('abc')
    expect(normalizeNote('a\u001fb\u007fc')).toBe('abc')
    // \t \n \r 是例外, 它们是正常输入(\t 会被压成普通空格)。
    expect(normalizeNote('a\tb')).toBe('a b')
    expect(normalizeNote('a\u000bb')).toBe('ab')
  })

  it('超长截断到上限, 且恰好等于上限时不截', () => {
    expect(normalizeNote('字'.repeat(MAX_NOTE + 50))).toHaveLength(MAX_NOTE)
    expect(normalizeNote('字'.repeat(MAX_NOTE))).toHaveLength(MAX_NOTE)
  })

  it('先裁剪再截断 —— 否则结尾的空格会吃掉正文的额度', () => {
    const out = normalizeNote('  ' + '字'.repeat(MAX_NOTE) + '  ')
    expect(out).toHaveLength(MAX_NOTE)
    expect(out.startsWith('字')).toBe(true)
  })

  it('计数器与真正发出去的东西一致', () => {
    // 这个计数器唯一的存在意义就是"让人当场看见"。它与 normalizeNote 用的是同一个函数,
    // 所以它们不可能不一致 —— 这一条钉的就是那个"同一个"。
    expect(noteRemaining('')).toBe(MAX_NOTE)
    expect(noteRemaining('abc')).toBe(MAX_NOTE - 3)
    // 已经超了就是 0, 不给负数。
    expect(noteRemaining('字'.repeat(MAX_NOTE + 10))).toBe(0)
  })
})

describe('normalizeShare —— 应用给的意图', () => {
  it('只取要用的那几个字段, 其余的丢掉', () => {
    const s = normalizeShare({
      title: '  五子棋  ',
      description: '来下一局',
      sessionId: 'sess-9',
      coverUrl: 'https://cdn.example.com/a.png',
      // 一个自己编的字段不该出现在结果里 —— 下一个人看到的就不该有它。
      admin: true,
    })
    expect(s).toEqual({
      title: '五子棋',
      description: '来下一局',
      sessionId: 'sess-9',
      coverUrl: 'https://cdn.example.com/a.png',
    })
  })

  it('长度与形状都被收 —— 这两个字段会进**别人**的消息流', () => {
    const s = normalizeShare({ title: 'x'.repeat(500), description: 'y'.repeat(500) })
    expect(s.title.length).toBeLessThanOrEqual(60)
    expect(s.description.length).toBeLessThanOrEqual(160)
  })

  it('类型不对的一律当作没给, 而不是抛', () => {
    // 这一头是第三方代码, 它可能发任何东西 —— 抛出去会让一次分享变成宿主的一屏白。
    const s = normalizeShare({ title: 42, description: ['x'], sessionId: {}, coverUrl: 7 })
    expect(s).toEqual({ title: '', description: '', sessionId: null, coverUrl: null })
  })

  it('sessionId 为空串 = "由宿主决定", 不是"分享一场不存在的会话"', () => {
    expect(normalizeShare({ sessionId: '' }).sessionId).toBeNull()
    expect(normalizeShare({}).sessionId).toBeNull()
  })
})

describe('isDisplayableCover —— 只放行 https 的绝对地址', () => {
  it('https 放行', () => {
    expect(isDisplayableCover('https://cdn.example.com/cover.png')).toBe(true)
  })

  it('http 拒 —— 这一页在 https 上, 浏览器会把它当混合内容拦掉', () => {
    // 表现为一张永远裂着的图。宁可退回平台自己画的那张。
    expect(isDisplayableCover('http://cdn.example.com/cover.png')).toBe(false)
  })

  it('data: 与 blob: 拒 —— 拒绝的理由不是安全, 而是它们没有意义', () => {
    // 一个 data URL 里嵌着的图片是那个应用自己画出来的, 而它就在页面里。
    // 放行它只会让"封面"变成一条可以塞任意体积内容的通路。
    expect(isDisplayableCover('data:image/png;base64,AAAA')).toBe(false)
    expect(isDisplayableCover('blob:https://chat.luxera.top/abc')).toBe(false)
  })

  it('空串与超长拒', () => {
    expect(isDisplayableCover('')).toBe(false)
    expect(isDisplayableCover('https://x/' + 'a'.repeat(2100))).toBe(false)
  })

  it('经过 normalizeShare 之后, 不可显示的封面变成 null 而不是一个坏地址', () => {
    expect(normalizeShare({ coverUrl: 'http://x/a.png' }).coverUrl).toBeNull()
    expect(normalizeShare({ coverUrl: 'data:image/png;base64,AA' }).coverUrl).toBeNull()
  })
})

// ─────────────────────────── 候选人 ───────────────────────────

function conv(over: Partial<ConversationSummary> & { id: string }): ConversationSummary {
  const { id, ...rest } = over
  return {
    id,
    // 一期里 peer.kind 恒为 'companion', 而这个文件不关心它 —— 它只认 peer.name。
    peer: { kind: 'companion', id: 'c-' + id, name: '某人' },
    title: '',
    lastMessage: null,
    lastMessageAt: null,
    unreadCount: 0,
    pinned: false,
    muted: false,
    ...rest,
  }
}

describe('filterRecipients —— 搜的是三个字段, 不是"名字"', () => {
  const maya = conv({ id: '1', peer: { kind: 'companion', id: 'u1', name: 'Maya' } })
  const titled = conv({ id: '2', title: '和小满的棋局', peer: { kind: 'companion', id: 'u2', name: '小满' } })
  const talked = conv({
    id: '3',
    peer: { kind: 'companion', id: 'u3', name: '阿岚' },
    lastMessage: {
      id: 'm3',
      senderType: 'user',
      senderId: 'u3',
      content: '上次那盘 maya 赢了',
      createdAt: '2026-09-17T00:00:00Z',
    },
  } as Partial<ConversationSummary> & { id: string })
  const none = conv({ id: '4', peer: { kind: 'companion', id: 'u4', name: '老周' } })
  const all = [maya, titled, talked, none]

  it('空查询返回**全部** —— 第一眼必须是"我有哪些人"', () => {
    // 返回空列表的话, 打开 ShareSheet 的那一屏长得像"搜不到任何人"。
    expect(filterRecipients(all, '')).toHaveLength(4)
    expect(filterRecipients(all, '   ')).toHaveLength(4)
  })

  it('名字命中排在最前, 内容命中排在最后', () => {
    // 用户搜"maya"时, 一个名字里没有 maya、只是最后一条消息里提到 maya 的会话排在第一行,
    // 会让人觉得搜索是坏的。
    const out = filterRecipients(all, 'maya')
    expect(out.map((c) => c.id)).toEqual(['1', '3'])
  })

  it('标题也参与匹配, 名次在名字之后', () => {
    const out = filterRecipients(all, '棋局')
    expect(out.map((c) => c.id)).toEqual(['2'])
  })

  it('大小写不敏感', () => {
    expect(filterRecipients(all, 'MAYA').map((c) => c.id)).toEqual(['1', '3'])
  })

  it('同档内保持调用方给的顺序 —— 最近说话的排前面', () => {
    // 二次排序会让"最近聊过的人在最上面"这条消失, 而那是打开面板时最有用的那条顺序。
    const a = conv({ id: 'a', peer: { kind: 'companion', id: 'ua', name: '棋友甲' } })
    const b = conv({ id: 'b', peer: { kind: 'companion', id: 'ub', name: '棋友乙' } })
    expect(filterRecipients([b, a], '棋友').map((c) => c.id)).toEqual(['b', 'a'])
  })

  it('搜不到就是空列表, 且不抛', () => {
    expect(filterRecipients(all, '不存在的人')).toEqual([])
    expect(filterRecipients([], 'maya')).toEqual([])
    // 对方还没有名字的会话行不该让整个搜索炸掉 —— PeerRef.name 本来就是可选的。
    const nameless = conv({ id: '5', peer: { kind: 'companion', id: 'c-5' } })
    expect(filterRecipients([nameless, maya], 'maya').map((c) => c.id)).toEqual(['1'])
    expect(filterRecipients([nameless, maya], '')).toHaveLength(2)
  })

  it('返回的是新数组 —— 调用方 sort 它不该动到 store 里的列表', () => {
    const out = filterRecipients(all, '')
    out.reverse()
    expect(all[0].id).toBe('1')
  })
})

describe('describeShareFailure —— 把服务端的拒绝翻成"接下来做什么"', () => {
  it('每一类失败都给出**下一步**, 而不是复述后端措辞', () => {
    // 分享失败恰恰是用户最需要知道"接下来做什么"的时刻: 是重试一次, 还是去找房主再开一局?
    const cases: [string, string][] = [
      ['SESSION_NOT_FOUND', '重开一局'],
      ['APPLICATION_SESSION_NOT_FOUND', '重开一局'],
      ['NOT_SESSION_OWNER', '只有开这一局的人'],
      ['NOT_PERMITTED', '只有开这一局的人'],
      ['INVITATION_EXHAUSTED', '名额用完'],
      ['SESSION_ENDED', '已经结束'],
      ['APPLICATION_NOT_AVAILABLE', '不能开新会话'],
    ]
    for (const [code, expected] of cases) {
      const msg = describeShareFailure(new ChatApplicationError('400', code, 'backend wording'))
      expect(msg, code).toContain(expected)
    }
  })

  it('认不出的码**原样显示**, 不吞掉', () => {
    // 吞掉的话, 一次没预料到的失败会表现为"什么都没发生" —— 那是最难被报上来的 bug 形状。
    const msg = describeShareFailure(new ChatApplicationError('500', 'SOMETHING_NEW', '后端原话'))
    expect(msg).toContain('SOMETHING_NEW')
    expect(msg).toContain('后端原话')
  })

  it('不是 ChatApplicationError 也给得出话', () => {
    expect(describeShareFailure(new Error('网络断了'))).toBe('网络断了')
    expect(describeShareFailure('随便一个值')).toBe('随便一个值')
  })
})

describe('coverOf —— 平台自己画的那张封面', () => {
  it('取首字, 且对同一个名字永远给同一个颜色', () => {
    // 一个每次刷新都换颜色的封面等于没有封面。
    const a = coverOf('五子棋')
    const b = coverOf('五子棋')
    expect(a).toEqual(b)
    expect(a.initial).toBe('五')
    expect(a.hue).toBeGreaterThanOrEqual(0)
    expect(a.hue).toBeLessThan(360)
  })

  it('不同名字给不同颜色 —— 否则邀请列表里所有应用长得一样', () => {
    const hues = new Set(['五子棋', '井字棋', '你画我猜', '斗地主'].map((n) => coverOf(n).hue))
    expect(hues.size).toBeGreaterThan(1)
  })

  it('名字为空时也给得出东西 —— 一张卡片不能因为没名字就少一块', () => {
    expect(coverOf('').initial).toBe('应')
    expect(coverOf('   ').initial).toBe('应')
  })

  it('首字取的是**整个字符**, 不是代理对的一半', () => {
    // 一个 emoji 名字取半个代理对会渲染成一个方块。
    expect(coverOf('🎲骰子').initial).toBe('🎲')
  })
})
