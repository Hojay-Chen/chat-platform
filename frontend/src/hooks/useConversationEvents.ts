import { useEffect, useRef } from 'react'
import { openEventStream } from '@/api/client'

/**
 * 事件流的**连接生命周期** —— 只管连上、断开、重连, 不管每条事件是什么意思。
 *
 * <h2>为什么要把连接和语义分开</h2>
 *
 * 老实现把两件事写在同一个 `useEffect` 里, 而那个 effect 的依赖是
 * `[companionId, activeConvId]` —— 于是**每切换一次会话就重开一条 SSE**。连接数不多时
 * 看不出问题, 但它同时是那个 bug 的成因: 事件处理器在整个 effect 里重建, 于是
 * "这条事件属于哪段会话"这件事只能在闭包里判, 而闭包里的 `activeConvIdRef` 是异步更新的。
 *
 * 分开之后: 连接只在伴侣变化时重建(这是对的 —— 事件流本来就是按伴侣开的), 而"这条事件
 * 是什么意思"由调用方在每次渲染时的最新闭包里判。**依赖数组里再也不会出现 conversationId。**
 *
 * `onEvent` 走 ref: 调用方每次渲染传进来的都可能是个新函数, 若进依赖数组就会重连。
 * 用 ref 意味着处理器永远是最新的那一版, 而连接始终只有一条。
 */
export type EventHandler = (event: string, data: unknown) => void

export function useConversationEvents(peerId: string | undefined, onEvent: EventHandler) {
  const handlerRef = useRef(onEvent)
  handlerRef.current = onEvent

  useEffect(() => {
    if (!peerId) return
    let close: (() => void) | null = null
    let cancelled = false

    openEventStream(peerId, (event, data) => {
      if (cancelled) return
      handlerRef.current(event, data)
    }).then((c) => {
      // 连接建立时组件可能已经卸载了 —— 那一句 `cancelled` 不是防御性代码,
      // 它是"卸载后仍然收到 close 回调"这条真实路径的唯一出口
      if (cancelled) c()
      else close = c
    })

    return () => {
      cancelled = true
      if (close) close()
    }
  }, [peerId])
}
