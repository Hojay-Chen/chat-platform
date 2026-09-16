import { useCallback, useEffect, useRef, useState } from 'react'
import type { Message } from '@/types'
import type { ConversationSummary } from '@/api/conversations'
import * as convApi from '@/api/conversations'
import { toMessage } from '@/lib/messageState'
import { classifyRoomEvent } from '@/lib/roomEvents'
import { useConversationStore } from '@/stores/conversations'
import { useBurstSend } from './useBurstSend'
import { useConversationEvents } from './useConversationEvents'

/**
 * 一间聊天室的全部状态。
 *
 * 会话由 `conversationId` 寻址 —— 这是这一期最要紧的一处修正。老实现里 URL 上只有伴侣 id,
 * 而"正在看哪一个会话"是组件里的一个 state, 于是**刷新页面会跳到第一个会话**而不是你
 * 正在看的那个。会话 id 进 URL 之后, 刷新、分享、后退都自然成立。
 *
 * <h2>这里修掉的那个 bug</h2>
 *
 * 老的事件处理器开头是 `if (convId !== activeConvIdRef.current) return` —— 于是**别的
 * 会话来了新消息时, 会话列表根本不刷新**, 而那恰恰是 IM 最需要的那条路径: 你正看着 A
 * 的聊天记录, B 发来一条消息, 列表上 B 那一行不动、角标不亮。
 *
 * 拆开那两件事的那一刀在 `lib/roomEvents.ts`, 连同它的回归测试。**这一个 hook 里没有
 * 一条判定** —— 它只负责按 `classifyRoomEvent` 给出的两个答案行动。这不是洁癖: 本仓
 * 前端测试跑在 `environment: 'node'` 上, 没有 jsdom, hook 渲染不起来, 所以任何写在
 * 这里的判断都不可能被测到。要保护那个修复, 它就必须住在一个能被断言的地方。
 *
 * 列表本身住在 store 里(单一数据源), 所以这里不需要知道刷新结果往哪儿放。
 */
export function useChatRoom(conversationId: string | undefined) {
  const [conv, setConv] = useState<ConversationSummary | null>(null)
  const [messages, setMessages] = useState<Message[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [typing, setTyping] = useState(false)
  const [streaming, setStreaming] = useState(false)
  const [readMap, setReadMap] = useState<Record<string, boolean>>({})
  const [statusMap, setStatusMap] = useState<Record<string, string>>({})

  const refresh = useConversationStore((s) => s.load)
  const patchConv = useConversationStore((s) => s.patch)

  // 事件处理器在异步回调里读它, 所以走 ref —— 见 useConversationEvents 的注释
  const convIdRef = useRef(conversationId)
  useEffect(() => {
    convIdRef.current = conversationId
  }, [conversationId])

  // ── 加载 ──────────────────────────────────────────────
  useEffect(() => {
    if (!conversationId) {
      setLoading(false)
      return
    }
    let cancelled = false
    setLoading(true)
    setConv(null)
    setMessages([])

    ;(async () => {
      try {
        const [summary, list] = await Promise.all([
          convApi.getConversation(conversationId),
          convApi.listMessages(conversationId),
        ])
        if (cancelled) return
        setConv(summary)
        setMessages(list)
        setError('')
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : '加载失败')
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()

    return () => {
      cancelled = true
    }
  }, [conversationId])

  // ── 已读 ──────────────────────────────────────────────
  /**
   * 读到某条消息为止。进入聊天室、以及窗口重新获得焦点时调它。
   *
   * 先把本地角标清掉再发请求: 用户已经**看着**这些消息了, 让红点再亮半秒是对已经发生
   * 的事撒谎。请求失败也不回滚 —— 下次轮询会把真实值拉回来, 而回滚会让红点闪一下。
   */
  const markRead = useCallback(async () => {
    const id = convIdRef.current
    if (!id) return
    patchConv(id, { unreadCount: 0 })
    try {
      await convApi.markRead(id)
    } catch {
      // 已读回执丢了不影响读消息本身
    }
  }, [patchConv])

  useEffect(() => {
    if (conv) void markRead()
  }, [conv, markRead])

  useEffect(() => {
    // 窗口重新获得焦点 —— 用户可能只是切走了一会儿, 回来时该看到的都该算读过
    const onFocus = () => void markRead()
    window.addEventListener('focus', onFocus)
    return () => window.removeEventListener('focus', onFocus)
  }, [markRead])

  // ── 事件 ──────────────────────────────────────────────
  const onEvent = useCallback(
    (event: string, data: unknown) => {
      const d = (data ?? {}) as Record<string, unknown>
      // 判定在 lib/roomEvents.ts 里, 那边有测试 —— 这里只负责按答案行动
      const { applyToRoom, refreshList } = classifyRoomEvent(event, data, convIdRef.current)

      // 归属: 事件里带了就用它, 没带就用当前打开的那段。
      // 走到这里时若 convId 非空, 它必然等于 convIdRef.current(见 classifyRoomEvent),
      // 所以这一个表达式覆盖了两种情况。
      const convId = convIdRef.current ?? ''
      const mid = String(d.messageId ?? '')

      if (applyToRoom) {
        if (event === 'message_read') {
          if (mid) {
            setReadMap((m) => ({ ...m, [mid]: true }))
            setStatusMap((m) => ({ ...m, [mid]: 'READ' }))
          }
        } else if (event === 'user_message_status') {
          if (mid) setStatusMap((m) => ({ ...m, [mid]: String(d.status ?? 'DELIVERED') }))
        } else if (event === 'companion_typing') {
          setTyping(Boolean(d.typing))
        } else if (event === 'message_created') {
          // 自己那条消息已落库 → temp 气泡换成 canonical(按 clientMessageId 匹配)
          const cid = String(d.clientMessageId ?? '')
          // 注意这里**没有 return**: 一个缺 messageId 的畸形帧不该让会话列表不刷新。
          // 老实现里这两处 `if (!mid) return` 正是同一个 bug 的小号版本。
          if (mid) {
            setMessages((prev) => {
              if (prev.some((m) => m.id === mid)) return prev // 幂等
              if (cid && prev.some((m) => m.clientMessageId === cid)) {
                return prev.map((m) =>
                  m.clientMessageId === cid
                    ? { ...m, id: mid, deliveryStatus: String(d.status ?? 'DELIVERED') }
                    : m,
                )
              }
              return [...prev, toMessage(mid, convId, 'user', String(d.content ?? ''), cid)]
            })
          }
        } else if (event === 'companion_message') {
          // 她发来的 → 增量追加, 不重载整个聊天记录(§十四)
          const content = String(d.content ?? '')
          const proactive = d.proactive === true
          if (mid) {
            setMessages((prev) => {
              if (prev.some((m) => m.id === mid)) return prev
              return [...prev, {
                ...toMessage(mid, convId, 'companion', content),
                proactive,
                messageKind: proactive ? 'PROACTIVE' : 'NORMAL',
              }]
            })
          }
        }
      }

      // ★ 见 `lib/roomEvents.ts`: 这一句在 `applyToRoom` 之外。会话列表要更新,
      //   与消息属不属于当前会话无关 —— 这一行就是那个 bug 的修复。
      if (refreshList) void refresh()
    },
    [refresh],
  )

  useConversationEvents(conv?.peer.id, onEvent)

  // ── 发送 ──────────────────────────────────────────────
  const peerId = conv?.peer.id

  const { send, gathering } = useBurstSend({
    conversationId: conversationId ?? '',
    // 一期全是数字人会话 —— 自适应窗口, 与老实现零行为差异。
    // 二期的真人会话传 0(回车即发), 这个参数就是那处开关。
    window: 'adaptive',
    post: (cid, batch) => {
      if (!peerId) throw new Error('会话还没加载完')
      return convApi.sendMessages(peerId, cid, batch)
    },
    onOptimistic: (content, clientMessageId) => {
      setMessages((prev) => [
        ...prev,
        {
          id: `temp-${clientMessageId}`,
          conversationId: conversationId ?? '',
          senderType: 'user',
          content,
          clientMessageId,
          createdAt: new Date().toISOString(),
          deliveryStatus: 'SENT',
        },
      ])
    },
    onCanonical: (canonicals) => {
      const withCid = canonicals.filter((c) => c.clientMessageId)
      if (withCid.length === 0) return
      setMessages((prev) => {
        const byCid = new Map(withCid.map((c) => [c.clientMessageId!, c]))
        let changed = false
        const next = prev.map((m) => {
          if (!m.id.startsWith('temp-') || !m.clientMessageId) return m
          const c = byCid.get(m.clientMessageId)
          if (!c) return m
          changed = true
          return {
            ...m,
            id: c.id,
            conversationId: c.conversationId,
            deliveryStatus: c.deliveryStatus || 'DELIVERED',
          }
        })
        return changed ? next : prev
      })
    },
    onFailed: (clientMessageIds, message) => {
      setError(message)
      // 标成失败, 不让用户以为发出去了 —— 把"发送失败"错判成"已发送"是最坏的一种错
      const ids = new Set(clientMessageIds)
      setMessages((prev) => prev.map((m) =>
        m.id.startsWith('temp-') && m.clientMessageId && ids.has(m.clientMessageId)
          ? { ...m, deliveryStatus: 'FAILED' }
          : m,
      ))
    },
    onStreamingChange: setStreaming,
  })

  return {
    conv, messages, loading, error, setError,
    typing, streaming, gathering,
    readMap, statusMap,
    send, markRead,
  }
}
