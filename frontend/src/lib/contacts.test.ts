import { describe, expect, it } from 'vitest'
import { contactLetters, filterContacts, shouldShowIndex, sortContacts } from './contacts'

const c = (name: string) => ({ name })

describe('通讯录排序', () => {
  it('中文按拼音排, 不是按码点', () => {
    // 码点序是 张(5F20) < 李(674E) < 王(738B), 拼音序是 李(li) < 王(wang) < 张(zhang)。
    // 一个通讯录如果不做这一步, 用户会觉得"顺序是乱的"—— 而它其实是"稳定地乱"。
    const sorted = sortContacts([c('张三'), c('王五'), c('李四')])
    expect(sorted.map((x) => x.name)).toEqual(['李四', '王五', '张三'])
  })

  it('中英混排不炸, 也不把英文全推到一头之外', () => {
    const sorted = sortContacts([c('张三'), c('Alice'), c('李四')])
    expect(sorted).toHaveLength(3)
    expect(new Set(sorted.map((x) => x.name)).size).toBe(3)
  })

  it('不改原数组', () => {
    const input = [c('张三'), c('李四')]
    const out = sortContacts(input)
    expect(out).not.toBe(input)
    expect(input.map((x) => x.name)).toEqual(['张三', '李四'])
  })

  it('名字缺失不炸', () => {
    expect(() => sortContacts([{ name: '' }, c('李四')])).not.toThrow()
  })
})

describe('通讯录搜索', () => {
  const list = [c('晚晚'), c('林夏'), c('Alice')]

  it('空查询返回全部', () => {
    expect(filterContacts(list, '')).toHaveLength(3)
    expect(filterContacts(list, '   ')).toHaveLength(3)
  })

  it('按名字子串匹配', () => {
    expect(filterContacts(list, '晚').map((x) => x.name)).toEqual(['晚晚'])
  })

  it('英文大小写不敏感', () => {
    expect(filterContacts(list, 'alice').map((x) => x.name)).toEqual(['Alice'])
    expect(filterContacts(list, 'ALI').map((x) => x.name)).toEqual(['Alice'])
  })

  it('搜不到就是空数组, 不是全部', () => {
    // 这一条是防"搜索框看起来坏了"的: 拼错一个字却返回全部, 用户会以为搜索没生效
    expect(filterContacts(list, '不存在的人')).toEqual([])
  })
})

describe('首字母索引该不该画', () => {
  it('全部是中文名时只有一个 #, 不画', () => {
    // 这是本平台的常态: Agent 名字来自 LLM, 几乎全是中文
    const letters = contactLetters([c('晚晚'), c('林夏'), c('张三')])
    expect(letters).toEqual(['#'])
    expect(shouldShowIndex(letters)).toBe(false)
  })

  it('真有跨字母的名字时才画', () => {
    const letters = contactLetters([c('晚晚'), c('Alice'), c('Bob')])
    expect(letters).toEqual(['#', 'A', 'B'])
    expect(shouldShowIndex(letters)).toBe(true)
  })

  it('空列表不画, 也不炸', () => {
    expect(contactLetters([])).toEqual([])
    expect(shouldShowIndex([])).toBe(false)
  })

  it('同一个字母只出现一次', () => {
    expect(contactLetters([c('Alice'), c('Amber'), c('Bob')])).toEqual(['A', 'B'])
  })
})
