import { describe, expect, it } from 'vitest'
import { avatarColor, groupCellCount, groupGridCols, indexLetter, initials } from './avatar'

describe('initials', () => {
  it('中文取第一个字', () => {
    expect(initials('阿离')).toBe('阿')
  })

  it('拉丁字母取首字母并大写', () => {
    expect(initials('alice')).toBe('A')
    expect(initials('Bob')).toBe('B')
  })

  it('首尾空格不影响', () => {
    expect(initials('  阿离  ')).toBe('阿')
  })

  it('空名字给 ? —— 纯色空方块看起来像加载失败', () => {
    expect(initials('')).toBe('?')
    expect(initials('   ')).toBe('?')
    expect(initials(undefined as unknown as string)).toBe('?')
  })

  it('emoji 不被切成半个', () => {
    // 用 Array.from 而不是 [0], 后者会把代理对切成孤立码元
    expect(initials('😀小明')).toBe('😀')
  })
})

describe('avatarColor', () => {
  it('同一个 id 永远同一个颜色 —— 否则头像每次渲染都变脸', () => {
    expect(avatarColor('companion-1')).toBe(avatarColor('companion-1'))
  })

  it('是 8 色板里的一员', () => {
    expect(avatarColor('x')).toMatch(/^#[0-9A-F]{6}$/i)
  })

  it('不同 id 大体上落到不同颜色', () => {
    // 不能断言"必不相同"（哈希会撞）, 但 20 个 uuid 全撞就说明哈希坏了
    const ids = Array.from({ length: 20 }, (_, i) => `550e8400-e29b-41d4-a716-44665544${i}00`)
    expect(new Set(ids.map(avatarColor)).size).toBeGreaterThan(3)
  })

  it('空 seed 给固定兜底色, 不给随机', () => {
    expect(avatarColor('')).toBe(avatarColor(''))
  })
})

describe('groupCellCount · 九宫格', () => {
  it('1~9 人各有各的格子', () => {
    expect(groupCellCount(1)).toBe(1)
    expect(groupCellCount(3)).toBe(3)
    expect(groupCellCount(9)).toBe(9)
  })

  it('超过 9 人只画 9 格', () => {
    expect(groupCellCount(12)).toBe(9)
    expect(groupCellCount(500)).toBe(9)
  })

  it('空群 0 格（调用方画兜底, 不是空白）', () => {
    expect(groupCellCount(0)).toBe(0)
  })
})

describe('groupGridCols', () => {
  it('1 人整格', () => {
    expect(groupGridCols(1)).toBe(1)
  })

  it('2~4 人 2 列', () => {
    expect(groupGridCols(2)).toBe(2)
    expect(groupGridCols(4)).toBe(2)
  })

  it('5 人以上 3 列', () => {
    expect(groupGridCols(5)).toBe(3)
    expect(groupGridCols(9)).toBe(3)
  })
})

describe('indexLetter · 通讯录字母索引', () => {
  it('拉丁首字母归到对应字母', () => {
    expect(indexLetter('alice')).toBe('A')
    expect(indexLetter('Zoe')).toBe('Z')
  })

  it('中文一律归到 #', () => {
    expect(indexLetter('阿离')).toBe('#')
  })

  it('空名字归到 #', () => {
    expect(indexLetter('')).toBe('#')
  })

  it('数字归到 #', () => {
    expect(indexLetter('123')).toBe('#')
  })
})
