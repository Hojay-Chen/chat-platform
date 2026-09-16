/** §七: 关系类型(与后端 RelationshipTypes.ALL 一致) */
export const RELATIONSHIP_TYPES = [
  { value: 'lover', label: '恋人', desc: '想要亲密又彼此独立的关系' },
  { value: 'best_friend', label: '最好的朋友', desc: '无话不谈,彼此信任' },
  { value: 'friend', label: '朋友', desc: '轻松自然地相处' },
  { value: 'sister', label: '姐姐/妹妹', desc: '像家人一样亲近' },
  { value: 'brother', label: '哥哥/弟弟', desc: '像家人一样亲近' },
  { value: 'colleague', label: '同事', desc: '工作上的伙伴' },
  { value: 'classmate', label: '同学', desc: '一起学习和成长的伙伴' },
  { value: 'family', label: '家人', desc: '血缘般的羁绊' },
  { value: 'mentor', label: '前辈/老师', desc: '值得尊敬和学习的对象' },
  { value: 'other', label: '自定义', desc: '由你和它共同定义' },
] as const

export type RelationshipTypeValue = (typeof RELATIONSHIP_TYPES)[number]['value']

export const RELATIONSHIP_TYPE_ZH: Record<string, string> = Object.fromEntries(
  RELATIONSHIP_TYPES.map((t) => [t.value, t.label]),
)

export function relationshipTypeZh(type?: string | null): string {
  if (!type) return '朋友'
  return RELATIONSHIP_TYPE_ZH[type] || RELATIONSHIP_TYPE_ZH['friend']
}
