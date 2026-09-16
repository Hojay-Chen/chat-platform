import { useCallback, useEffect, useRef, useState } from 'react'
import { recordGap, resolveDelay } from '@/lib/burstWindow'
import type { CanonicalMessage, OutgoingMessage } from '@/api/conversations'

/**
 * 连发的发送状态机 —— 从 `Chat.tsx:241-371` 抽出来的。
 *
 * <h2>为什么连发聚合必须留着</h2>
 *
 * 后端的 `MessageController` 与 `Chat.tsx` 的注释是同一句话: **一次请求至多一次回复**。
 * 用户打"在吗"再打"我想你了", 若分两次请求, 对面就会回两次。而真人收到连发也只回一次
 * —— 所以聚合恰恰是在仿真人类行为, 删掉它是行为回归, 不是重构。
 *
 * <h2>这个 hook 里没有一条判定</h2>
 *
 * "等多久"这件事全部在 `lib/burstWindow.ts` 里, 而那个文件是被测过的。这里只做三件事:
 * 攒队列、起定时器、把结果交回给调用方。本仓的前端测试跑在 `environment: 'node'` 上,
 * 没有 jsdom —— hook 渲染不起来, 所以**任何写在这里的判断都不可能被测到**。
 * 这条分工不是洁癖, 它是这个测试环境下唯一能让逻辑被验证的形状。
 *
 * 回调走 ref 而不是依赖数组: `send` 必须是一个稳定引用, 否则调用方每次渲染都会拿到
 * 新函数, 而它被用在 `Composer` 的 props 上。用依赖数组的写法会让 `send` 在每次
 * `messages` 变化时重建 —— 那正是这类 hook 最常见的 stale closure 来源。
 */

export interface BurstSendOptions {
  /** 这批消息属于哪段会话。发出去时用的是**入队时**的那一个, 不是发出时的 */
  conversationId: string
  /**
   * `'adaptive'` = 按用户近期节奏自适应(数字人会话, 一期零行为回归);
   * 一个数字 = 固定窗口(二期的真人会话传 0, 回车即发)。
   */
  window: 'adaptive' | number
  post: (conversationId: string, batch: OutgoingMessage[]) => Promise<{
    messages?: CanonicalMessage[] | null
  }>
  /** 乐观气泡先画出来 —— 消息发出去之前用户就该看见它 */
  onOptimistic: (content: string, clientMessageId: string) => void
  /** 服务端回传了 canonical 消息(按 clientMessageId 对上 temp 气泡) */
  onCanonical: (canonicals: CanonicalMessage[]) => void
  onFailed: (clientMessageIds: string[], error: string) => void
  /** 请求在飞。**不再用它锁输入框** —— 那是"等她说完你再说"的语汇, IM 里是错的 */
  onStreamingChange?: (streaming: boolean) => void
}

/** 入队时就把会话 id 记下来 —— 切走再切回来时, 这批消息仍然属于它被敲下的那段对话 */
interface PendingMessage extends OutgoingMessage {
  conversationId: string
}

export function useBurstSend(opts: BurstSendOptions) {
  const optsRef = useRef(opts)
  optsRef.current = opts

  const pendingRef = useRef<PendingMessage[]>([])
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const batchStartRef = useRef(0)
  const gapsRef = useRef<number[]>([])
  const lastSendRef = useRef(0)
  const counterRef = useRef(0)
  const [gathering, setGathering] = useState(false)

  const clearTimer = useCallback(() => {
    if (timerRef.current) clearTimeout(timerRef.current)
    timerRef.current = null
  }, [])

  /** 把队列里那一批送出去。空队列直接返回 —— 定时器与"窗口为 0"两条路都会走到这里 */
  const fire = useCallback(async () => {
    const batch = pendingRef.current
    if (batch.length === 0) return
    pendingRef.current = []

    const o = optsRef.current
    o.onStreamingChange?.(true)
    try {
      const res = await o.post(batch[0].conversationId, batch.map((b) => ({
        content: b.content,
        clientMessageId: b.clientMessageId,
      })))
      o.onCanonical(res.messages ?? [])
    } catch (e) {
      o.onFailed(batch.map((b) => b.clientMessageId), e instanceof Error ? e.message : '发送失败')
    } finally {
      o.onStreamingChange?.(false)
    }
  }, [])

  const send = useCallback((raw: string) => {
    const content = raw.trim()
    if (!content) return

    const o = optsRef.current
    const clientMessageId = `c-${Date.now()}-${counterRef.current++}`
    o.onOptimistic(content, clientMessageId)

    const now = Date.now()
    const isFirst = pendingRef.current.length === 0
    pendingRef.current = [...pendingRef.current, { content, clientMessageId, conversationId: o.conversationId }]
    if (isFirst) batchStartRef.current = now
    gapsRef.current = recordGap(gapsRef.current, now - (lastSendRef.current || now))
    lastSendRef.current = now

    const delay = resolveDelay(o.window, gapsRef.current, now - batchStartRef.current)
    clearTimer()
    if (delay <= 0) {
      setGathering(false)
      void fire()
      return
    }
    setGathering(true)
    timerRef.current = setTimeout(() => {
      timerRef.current = null
      setGathering(false)
      void fire()
    }, delay)
  }, [clearTimer, fire])

  /**
   * 切换会话时丢掉没发出去的那一批。
   *
   * 丢掉而不是"发到新会话去": 用户敲那几句话的时候看的是另一段对话, 把它们投到别处
   * 是把一句私房话发给另一个人。丢弃是有代价的, 但那个代价是看得见的(消息没发出去),
   * 而投错人的代价是看不见的。
   */
  useEffect(() => {
    clearTimer()
    pendingRef.current = []
    setGathering(false)
    return clearTimer
  }, [opts.conversationId, clearTimer])

  return { send, gathering }
}
