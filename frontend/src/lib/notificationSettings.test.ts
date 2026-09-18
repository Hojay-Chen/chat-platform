import { describe, expect, it } from 'vitest'
import { SWITCH_COPY, copyOf, mutedCount, settingNote, withSetting } from './notificationSettings'

/**
 * 这一份测的不是"函数能跑", 是**那两句话还在不在**。
 *
 * `notificationSettings.ts` 里 `SWITCH_COPY` 那几条文案, 是这个产品里唯一会被实现悄悄
 * 破坏的东西: 只要有人在服务端的通知载荷里塞一个发信人, 「免打扰」就从"平台根本不发"
 * 变成"发了但她没看" —— 而**没有任何编译期错误、没有任何接口变化、没有任何前端代码需要
 * 改**。用户也不会知道自己的哪一句话落了空。
 *
 * 所以这里对文案做内容断言, 而不是只断言"返回了一个非空字符串"。这类断言通常被嫌脆,
 * 但脆在这里正是要的东西: 谁改坏了那两句话, 谁就得来这里说明理由。
 */

describe('SWITCH_COPY · 免打扰那一句', () => {
  const muted = copyOf('muted')

  it('打开之后的说明里写着"平台不会发", 而不只是"更安静"', () => {
    // 这一条是本文件存在的理由。写成"消息会安静地到达"之类的措辞也能让开关看起来
    // 是好的, 但那句话描述的是**另一件事** —— 而实现会照着那句话去写。
    expect(muted.on).toContain('不会')
    expect(muted.on).toMatch(/不响|不会响/)
  })

  it('它还说明了"她只有自己去看手机时才知道" —— 这是免打扰之后唯一的事实', () => {
    expect(muted.on).toMatch(/主动|自己去看/)
  })

  it('`scope` 明说这一层替不了她那边的音量与免打扰', () => {
    // 两层免打扰(平台发不发 / 她的手机怎么响)是整个产品最容易被合并成一层的地方,
    // 合并之后的症状是"我设了免打扰, 她的闹钟还在响"。
    expect(muted.scope).toMatch(/音量|免打扰/)
  })

  it('关着的状态说的是"不设它会发生什么", 不是一句"已关闭"', () => {
    // 一个只写"已关闭"的开关没有告诉任何人默认行为是什么 —— 而默认行为恰好是
    // 这个开关唯一需要被理解的另一半。
    expect(muted.off).not.toMatch(/已关闭|关闭/)
    expect(muted.off.length).toBeGreaterThan(10)
  })
})

describe('SWITCH_COPY · 置顶那一句', () => {
  const pinned = copyOf('pinned')

  it('明说置顶只对**你自己**有效', () => {
    // 一个把置顶读成"优先找她"的用户会以为自己提高了这条消息的优先级, 而它只是
    // 一个本地排序字段。这一句是那个误解唯一的拦路文字。
    expect(pinned.scope).toMatch(/只对|只影响/)
  })

  it('明说它不改变她那边的任何东西', () => {
    expect(pinned.scope).toMatch(/她/)
  })

  it('标题是「置顶聊天」, 与「消息免打扰」对称', () => {
    // 两个标题都是"动作 + 对象"的四字/五字写法。缩成「置顶」「免打扰」在开关旁边
    // 也读得通, 但那两个词都是**动词**, 而开关要说的是一件**一直在生效的事**。
    expect(pinned.title).toBe('置顶聊天')
    expect(copyOf('muted').title).toBe('消息免打扰')
  })
})

describe('copyOf', () => {
  it('两个 key 都取得到, 且各是各的 —— 取错会画出另一个开关的说明', () => {
    expect(copyOf('muted').key).toBe('muted')
    expect(copyOf('pinned').key).toBe('pinned')
  })

  it('两条文案的 `on` 不一样 —— 相同的话说明有一条被复制粘贴忘了改', () => {
    expect(copyOf('muted').on).not.toBe(copyOf('pinned').on)
  })

  it('表里正好两条 —— 多一条意味着有一个开关没有调用方', () => {
    expect(SWITCH_COPY).toHaveLength(2)
    expect([...SWITCH_COPY.map((c) => c.key)].sort()).toEqual(['muted', 'pinned'])
  })

  it('每个 key 在表里只出现一次 —— 重复时 `find` 只认第一条, 另一条静静失效', () => {
    const keys = SWITCH_COPY.map((c) => c.key)
    expect(new Set(keys).size).toBe(keys.length)
  })
})

describe('settingNote', () => {
  it('两个都没设时返回 null —— 而不是编一句"正常"', () => {
    // 页头副标题的优先级是 `正在输入… > settingNote() > 静态说明`。返回一个假的
    // 非空值会把那条静态说明永远挤掉, 页头从此一直写着"正常"。
    expect(settingNote(false, false)).toBeNull()
  })

  it('只设了免打扰', () => {
    expect(settingNote(true, false)).toBe('免打扰')
  })

  it('只设了置顶', () => {
    expect(settingNote(false, true)).toBe('置顶')
  })

  it('两个都设了 —— 两个词都在, 顺序固定', () => {
    expect(settingNote(true, true)).toBe('免打扰 · 置顶')
  })
})

describe('mutedCount', () => {
  it('数的是 `muted === true` 的会话, 不是"不是 false"的会话', () => {
    // 列表里 `muted` 可能是 `undefined`(服务端没下发这一项)。用 `!c.muted` 的反面去数
    // 会把 undefined 数进去, 于是一个都没静音的时候「我」那一页也会显示"有 3 段被静音"。
    expect(mutedCount([{ muted: true }, {}, { muted: false }, { muted: undefined }])).toBe(1)
  })

  it('空列表是 0', () => {
    expect(mutedCount([])).toBe(0)
  })

  it('全静音时等于长度', () => {
    expect(mutedCount([{ muted: true }, { muted: true }])).toBe(2)
  })
})

describe('withSetting', () => {
  const list = [
    { id: 'a', muted: false, title: '甲' },
    { id: 'b', muted: false, title: '乙' },
  ]

  it('只改中了那一行', () => {
    const next = withSetting(list, 'b', { muted: true })
    expect(next[0].muted).toBe(false)
    expect(next[1].muted).toBe(true)
  })

  it('没动的那一行是**同一个对象** —— 前提是它真的一格都没变', () => {
    // 不要求它是新对象, 但要求原数组不被改。这里两条一起断言, 因为"返回新数组"
    // 与"不改原对象"是两件事, 而 Zustand 的 selector 对后者敏感。
    const next = withSetting(list, 'b', { muted: true })
    expect(next).not.toBe(list)
    expect(list[1].muted).toBe(false)
  })

  it('改的值是覆盖, 不是替换 —— 其余字段还在', () => {
    const next = withSetting(list, 'a', { muted: true })
    expect(next[0].title).toBe('甲')
  })

  it('id 不存在时原样返回, 不抛也不静默加一行', () => {
    // 「我」那一页的开关与聊天室那一个是同一份数据的两个入口。并发时可能出现
    // "我点的那一行已经被别处删掉了", 那种情况的下场必须是"什么都没发生"。
    const next = withSetting(list, 'zzz', { muted: true })
    expect(next).toHaveLength(2)
    expect(next.map((c) => c.id)).toEqual(['a', 'b'])
  })

  it('空覆盖不改变任何值', () => {
    expect(withSetting(list, 'a', {})).toEqual(list)
  })
})
