import { useEffect, useRef, useState } from 'react'

/**
 * 按一个身份键拉一块数据。资料页的四个面板、「我 → 提醒 / 通知」都用它。
 *
 * <h2>它替掉的不是"几行样板", 是一个真的 bug</h2>
 *
 * 老 `Chat.tsx` 里的面板是这么写的:
 *
 * ```ts
 * useEffect(() => { (async () => { setLife(await api.get(...)) })() }, [companionId])
 * ```
 *
 * 没有取消、没有先清空。于是从 A 的资料页切到 B 的时候, B 的页面上会先显示 **A 的
 * 生活、A 的关系、A 的记忆**, 直到请求回来才换掉 —— 而如果那两个请求的返回顺序颠倒,
 * 结果是 A 和 B 的数据混在同一屏上。用户看到的是一份不存在的人。
 *
 * 这个 hook 做两件事: **key 一变就立刻清空**(不显示上一个人的数据), 以及**卸载/换人
 * 之后回来的响应直接丢掉**(不覆盖新数据)。
 *
 * <h2>`initial` 与 `load` 为什么进 ref</h2>
 *
 * 它们都是调用方每次渲染新建的字面量(`[]`、`{}`)或箭头函数。放进 deps 会让 effect
 * 每渲染一次就重跑一次 —— 那是无限循环。放进 ref 之后, effect 的依赖只剩
 * `resourceKey` 一个, 而那正是这段数据真正的身份。
 */

export interface AgentData<T> {
  data: T
  loading: boolean
  /** 空串表示没错。**不抛异常**: 一个面板加载失败该在面板里说, 不该把整页炸掉 */
  error: string
  /**
   * 重拉一次。
   *
   * 面板里有写操作时必须有它: 删掉一条记忆之后要重拉列表, 而 effect 只认
   * `companionId`。让调用方自己"再拉一次然后覆盖 hook 的 data"是错的 ——
   * 那样 hook 手里的 `data` 与被渲染的那一份就分家了, 下一次 `companionId` 变化时
   * hook 会用一份过期的数据把界面刷回去。
   */
  reload: () => void
}

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : '加载失败'
}

export function useAgentData<T>(
  /**
   * 这块数据的身份。绝大多数时候是 `companionId`, 而「我 → 提醒」那里是**当前有哪些
   * Agent** 拼起来的串 —— 它的数据来自 N 个 Agent, 身份就是那 N 个 id 的集合。
   * 名字不叫 `companionId`, 是因为叫了它, 那个页面就只能硬塞一个假的 id 进来。
   */
  resourceKey: string | undefined,
  load: (id: string) => Promise<T>,
  initial: T,
): AgentData<T> {
  const loadRef = useRef(load)
  loadRef.current = load
  const initialRef = useRef(initial)

  const [state, setState] = useState<Omit<AgentData<T>, 'reload'>>({
    data: initialRef.current,
    loading: Boolean(resourceKey),
    error: '',
  })
  const [nonce, setNonce] = useState(0)

  useEffect(() => {
    if (!resourceKey) {
      setState({ data: initialRef.current, loading: false, error: '' })
      return
    }

    let cancelled = false
    // 先清空再拉 —— 见文件头: 这一行就是"看到上一个 Agent 的数据"那个 bug 的修复
    setState({ data: initialRef.current, loading: true, error: '' })

    loadRef.current(resourceKey).then(
      (data) => {
        if (!cancelled) setState({ data, loading: false, error: '' })
      },
      (e) => {
        if (!cancelled) setState({ data: initialRef.current, loading: false, error: messageOf(e) })
      },
    )

    return () => {
      cancelled = true
    }
  }, [resourceKey, nonce])

  return { ...state, reload: () => setNonce((n) => n + 1) }
}
