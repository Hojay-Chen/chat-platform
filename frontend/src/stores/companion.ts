import { create } from 'zustand'
import type { Companion } from '@/types'
import { api } from '@/api/client'

interface CompanionState {
  companions: Companion[]
  loading: boolean
  load: () => Promise<void>
  add: (c: Companion) => void
  /**
   * 就地改一行 —— 改账号ID 之后调它。
   *
   * <p>为什么不是"改完重拉一次": 这份数据**同时**供着通讯录和聊天列表的账号ID(聊天
   * 列表是拿它做本地 join 的, 见 `lib/handles.ts`)。重拉是整表替换, 而这一行改动只影响
   * 一行; 更实际的理由是重拉会让通讯录在改完号的瞬间闪一下"加载中"。
   *
   * <p>与 `useConversationStore.patch` 同名同义 —— 两处都是"一次点击之后不必重拉"。
   */
  patch: (id: string, over: Partial<Companion>) => void
  remove: (id: string) => void
}

export const useCompanionStore = create<CompanionState>((set) => ({
  companions: [],
  loading: false,

  load: async () => {
    set({ loading: true })
    try {
      const list = await api.get<Companion[]>('/api/companions')
      set({ companions: list })
    } finally {
      set({ loading: false })
    }
  },

  add: (c) => set((s) => ({ companions: [...s.companions, c] })),

  patch: (id, over) =>
    set((s) => ({ companions: s.companions.map((c) => (c.id === id ? { ...c, ...over } : c)) })),

  remove: (id) => set((s) => ({ companions: s.companions.filter((c) => c.id !== id) })),
}))
