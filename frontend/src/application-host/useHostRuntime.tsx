import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth'
import { createApplicationBridge, type HostHandlers } from './bridge'
import { surfaceGrants, type ApplicationUser } from './capabilities'
import { normalizeShare, type NormalizedShare } from './share'
import ChatOverlay, { type OverlayMode } from './ChatOverlay'
import ShareSheet from './ShareSheet'
import type { SurfaceType } from '@/api/lap'

/**
 * HostRuntime —— <b>宿主这一侧的全部状态</b>, 以及把它交给被嵌进来的应用的那根线。
 *
 * <h2>为什么需要一个 Provider, 而不是每个 iframe 自己管自己</h2>
 *
 * 因为 §7 的聊天浮窗与 §10 的分享单子**不属于任何一个应用**: 它们是平台的。一个 FULL_PAGE
 * 应用叫出浮窗之后, 用户切去另一个应用, 浮窗仍然该在那里 —— 它是用户的聊天, 不是那个应用
 * 的附属品。所以浮窗是一次一个(整个页面只有一个), 而应用是可以有好几个的。
 *
 * 于是状态分成两层:
 *
 * <ul>
 *   <li><b>每个应用一份</b>: 一条 {@code ApplicationBridge}。它认自己的 iframe, 权限来自
 *       自己那一种 Surface, 回执发给自己。见 {@link HostRuntime.attach}。</li>
 *   <li><b>整个页面一份</b>: 浮窗的开合与档位、分享单子的内容。它们住在这里。</li>
 * </ul>
 *
 * <h2>没有 Provider 时会怎样</h2>
 *
 * {@link useHostRuntime} 返回 `null`, 应用照常渲染, 只是叫不动浮窗、也分享不出去。这不是
 * 一个降级路径, 而是**两种真实的用法**: 应用详情页里预览一个应用、以及 `SurfaceHost` 的
 * 15 条渲染断言 —— 那两种情况下本来就没有"用户邀请某个人"这回事。
 *
 * <h2>这里为什么可以直接用 `window`</h2>
 *
 * 这个文件只在浏览器里跑(它挂在路由树上, 而那棵树是 `App.tsx` 挂的), 而且没有一条断言需要
 * 覆盖到它 —— 需要被断言的东西全部在 `bridge.ts` / `protocol.ts` / `share.ts` 这三个纯模块
 * 里。这一点是刻意的: 本仓前端测试跑在 `environment: 'node'` 上, 任何写在这里的判断都
 * 不可能被测到。
 */

/** 一个挂上来的应用。 */
export interface MountedApp {
  applicationId: string
  sessionId: string
  surface: SurfaceType
  /** 应用请求关掉自己。缺省时"关掉"什么都不做 —— 但回执仍然是 ok。 */
  onClose?: () => void
}

export interface HostRuntime {
  /**
   * 把一个 iframe 接到宿主上。返回的函数的唯一职责是 `dispose()` ——
   * 组件卸载时必须调, 否则每挂一次就多压一个监听器, 而它们全都还活着。
   */
  attach(frame: HTMLIFrameElement, app: MountedApp): () => void
  /** 应用报到过了没 —— 宿主据此收掉加载态。 */
  isReady(sessionId: string): boolean
  /** 叫出聊天浮窗。 */
  openChat(conversationId?: string, mode?: OverlayMode): void
}

const HostRuntimeContext = createContext<HostRuntime | null>(null)

/** 拿宿主。**可能为 null** —— 见文件头那段。 */
export function useHostRuntime(): HostRuntime | null {
  return useContext(HostRuntimeContext)
}

/** 分享单子这一次要显示什么。 */
interface SheetState {
  applicationId: string
  sessionId: string
  share: NormalizedShare
}

export function HostRuntimeProvider({
  children,
  /** 这一层应用是被哪段对话打开的(§17 的 `conversationId`)。从分享链接直接进来时为空。 */
  conversationId,
}: {
  children: ReactNode
  conversationId?: string
}) {
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)

  const [ready, setReady] = useState<ReadonlySet<string>>(() => new Set())
  const [overlay, setOverlay] = useState<{ open: boolean; mode: OverlayMode; conversationId: string | null }>(
    { open: false, mode: 'MINIMIZED', conversationId: null },
  )
  const [sheet, setSheet] = useState<SheetState | null>(null)
  /** 分享单子的结果往哪儿送 —— 应用此刻正 await 这一个 Promise。 */
  const sheetResolve = useRef<((result: { sent: boolean; conversationId?: string }) => void) | null>(null)
  const [toast, setToast] = useState('')

  /**
   * 用户身份的公开面。
   *
   * **只有三个字段**, 见 `capabilities.ts` 的 `ApplicationUser` 与 §15 的禁令清单: 一个
   * 第三方应用不该拿到用户的邮箱、生日、时区 —— 它要的只是"在界面上写谁在玩"。
   */
  const publicUser = useMemo<ApplicationUser>(
    () => ({
      id: user?.id ?? '',
      displayName: user?.nickname || user?.username || '',
    }),
    [user],
  )

  const announce = useCallback((text: string) => {
    setToast(text)
    window.setTimeout(() => setToast((t) => (t === text ? '' : t)), 2600)
  }, [])

  const closeSheet = useCallback((result: { sent: boolean; conversationId?: string }) => {
    // 先取出 resolver 再清 state —— 反过来的话, 应用会一直 await 一个永远不会 resolve 的
    // Promise, 而它的症状是"分享完了但游戏里的按钮一直在转圈"。
    const resolve = sheetResolve.current
    sheetResolve.current = null
    setSheet(null)
    resolve?.(result)
  }, [])

  const attach = useCallback(
    (frame: HTMLIFrameElement, app: MountedApp) => {
      const handlers: HostHandlers = {
        'app.ready': () => {
          setReady((prev) => {
            if (prev.has(app.sessionId)) return prev
            const next = new Set(prev)
            next.add(app.sessionId)
            return next
          })
          return { ok: true }
        },

        'app.close': () => {
          app.onClose?.()
          return { closed: true }
        },

        'user.me': () => publicUser,

        'chat.openWindow': (input) => {
          const wanted = typeof input.conversationId === 'string' ? input.conversationId : null
          // 见 ChatOverlay 的类注释: 点名要看某段对话就是展开, 否则先给一个最小条。
          const mode = (input.mode as OverlayMode | undefined) ?? (wanted ? 'EXPANDED' : 'MINIMIZED')
          setOverlay({ open: true, mode, conversationId: wanted ?? conversationId ?? null })
          return { open: true, mode }
        },

        'chat.minimize': () => {
          // 幂等: 浮窗还没开时就"最小化"不会报错, 也不会凭空开一个窗出来。
          setOverlay((o) => (o.open ? { ...o, mode: 'MINIMIZED' } : o))
          return { mode: 'MINIMIZED', open: false }
        },

        'chat.openConversation': (input) => {
          const id = String(input.conversationId)
          // 与浮窗不同, 这是"离开这个应用" —— 所以先把浮窗收掉, 别让它跟到聊天页上去。
          setOverlay((o) => ({ ...o, open: false }))
          navigate(`/chat/${encodeURIComponent(id)}`)
          return { opened: true }
        },

        'share.request': (input) => {
          const share = normalizeShare(input)
          // 已经有一张单子开着时, 直接把它换掉 —— 但**先让上一张的 await 结束**,
          // 否则上一个应用会永远等在那里。
          closeSheet({ sent: false })
          setSheet({
            applicationId: app.applicationId,
            sessionId: share.sessionId ?? app.sessionId,
            share,
          })
          return new Promise<{ sent: boolean; conversationId?: string }>((resolve) => {
            // 取消是正常结果, 不是异常: 应用拿到的是 `{sent: false}`, 而它该做的是把按钮
            // 恢复原状 —— 那正是 §9 定的形状。
            sheetResolve.current = resolve
          })
        },
      }

      const bridge = createApplicationBridge({
        port: window,
        // 每次取**当下**的 contentWindow —— iframe 会重挂, 把引用写死会让重挂之后
        // 所有回执都发给一个不存在的窗口。
        target: () => frame.contentWindow,
        // `conversationId` 取 Provider 收到的那一个, 不是每个应用各传一份 —— 应用是被
        // 哪段对话打开的, 那是**页面**的事实。让每个 attach 各自传, 迟早有一个传错。
        session: () => ({
          applicationId: app.applicationId,
          sessionId: app.sessionId,
          surface: app.surface,
          conversationId,
          permissions: surfaceGrants(app.surface),
        }),
        handlers,
        onRejected: (reason) => {
          // 这是"对面版本比我们新"的唯一线索, 所以不能静默。
          console.warn('[luxera-host] 丢掉一帧:', reason)
        },
      })

      return () => bridge.dispose()
    },
    [closeSheet, conversationId, navigate, publicUser],
  )

  const runtime = useMemo<HostRuntime>(
    () => ({
      attach,
      isReady: (sessionId) => ready.has(sessionId),
      openChat: (id, mode) =>
        setOverlay({
          open: true,
          mode: mode ?? (id ? 'EXPANDED' : 'MINIMIZED'),
          conversationId: id ?? conversationId ?? null,
        }),
    }),
    [attach, ready, conversationId],
  )

  return (
    <HostRuntimeContext.Provider value={runtime}>
      {children}

      {overlay.open && (
        <ChatOverlay
          conversationId={overlay.conversationId}
          mode={overlay.mode}
          onModeChange={(mode) => setOverlay((o) => ({ ...o, mode }))}
          onClose={() => setOverlay((o) => ({ ...o, open: false }))}
          onPick={(id) => setOverlay((o) => ({ ...o, conversationId: id, mode: 'EXPANDED' }))}
        />
      )}

      {sheet && (
        <ShareSheet
          share={sheet.share}
          sessionId={sheet.sessionId}
          onCancel={() => closeSheet({ sent: false })}
          onSent={(target) => {
            closeSheet({ sent: true, conversationId: target })
            announce(shareSentHint(sheet.share.title))
          }}
        />
      )}

      {/* `shadow-pop` 而不是 `shadow-lg` —— 它是浮层, 而全项目只有 `pop` 一个阴影 token
          (见 tailwind.config.js)。`shadow-lg` 是 Tailwind 的默认值, 绕过了那套 token。 */}
      {toast && (
        <div
          role="status"
          className="pointer-events-none fixed bottom-24 left-1/2 z-[80] -translate-x-1/2 rounded-full bg-ink/90 px-4 py-2 text-xs text-surface shadow-pop"
        >
          {toast}
        </div>
      )}
    </HostRuntimeContext.Provider>
  )
}

/**
 * 分享成功的那一句话。
 *
 * 有标题时说标题 —— 用户刚在单子上看过那个名字, 回一句没有名字的"已发送"会让他怀疑
 * 自己发的是不是那个。没有标题(应用没给)时就只说事实。
 */
function shareSentHint(title: string): string {
  return title
    ? `「${title}」已经发出去了, 链接在那段对话里。`
    : '已经发出去了, 链接在那段对话里。'
}
