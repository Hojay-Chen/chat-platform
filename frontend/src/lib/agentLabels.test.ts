import { describe, expect, it } from 'vitest'
import {
  changeSourceZh,
  factLabel,
  memoryTypeZh,
  notificationTypeZh,
  reminderTypeZh,
  stageZh,
  traitZh,
  zhPredicate,
} from './agentLabels'

/**
 * 这些对照表的**唯一**约定是: 认得出就翻译, 认不出就原样返回。
 *
 * 每一条 `?? 原值` 的兜底都单独钉一遍, 因为写漏一个的症状是「资料页上有一行是空白」——
 * 而空白看起来像数据丢了, 会把人引去查后端。
 */
describe('agentLabels', () => {
  it('认识的值翻成中文', () => {
    expect(memoryTypeZh('episodic')).toBe('经历')
    expect(memoryTypeZh('semantic')).toBe('认知')
    expect(memoryTypeZh('shared')).toBe('共同')
    expect(reminderTypeZh('user_set')).toBe('自定义')
    expect(notificationTypeZh('proactive')).toBe('主动找你')
    expect(traitZh('warmth')).toBe('温柔')
    expect(changeSourceZh('evolution')).toBe('自动演化')
  })

  it('不认识的值原样返回, 不吞成空串', () => {
    // 后端加一个新的记忆类型时, 界面该显示那个词, 而不是什么都不显示
    expect(memoryTypeZh('procedural')).toBe('procedural')
    expect(reminderTypeZh('weekly_digest')).toBe('weekly_digest')
    expect(notificationTypeZh('digest')).toBe('digest')
    expect(traitZh('grit')).toBe('grit')
    expect(changeSourceZh('imported')).toBe('imported')
  })

  it('关系阶段缺省是「初识」, 不是空白', () => {
    expect(stageZh('close')).toBe('亲密')
    expect(stageZh('deeply_connected')).toBe('深深相连')
    // 老代码 `STAGE_ZH[x]` 在这两种输入下都会渲染出 undefined, 资料页最显眼的一行变空白
    expect(stageZh(null)).toBe('初识')
    expect(stageZh(undefined)).toBe('初识')
    expect(stageZh('engaged')).toBe('engaged')
  })

  it('谓词认不出时拼出来的句子仍然读得懂', () => {
    expect(factLabel('likes', '咖啡')).toBe('用户喜欢咖啡')
    expect(factLabel('works_as', '老师')).toBe('用户工作是老师')
    // 认不出谓词: "用户 用户 咖啡" 还读得懂; "用户  咖啡" 只是坏掉了
    expect(zhPredicate('commutes_by')).toBe('commutes_by')
    expect(factLabel('commutes_by', '地铁')).toBe('用户commutes_by地铁')
  })

  it('宾语缺失时不留下 "null" 两个字', () => {
    expect(factLabel('likes', null)).toBe('用户喜欢')
    expect(factLabel('likes', undefined)).toBe('用户喜欢')
  })

  it('changeSource 为空时返回空串(那一行整个不画)', () => {
    expect(changeSourceZh(null)).toBe('')
    expect(changeSourceZh(undefined)).toBe('')
    expect(changeSourceZh('')).toBe('')
  })
})
