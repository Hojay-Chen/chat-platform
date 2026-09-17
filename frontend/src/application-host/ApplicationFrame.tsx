import { useEffect, useRef, useState } from 'react'
import type { SurfaceType } from '@/api/lap'
import { isCrossOrigin } from '@/surfaces/entry'
import { useHostRuntime } from './useHostRuntime'

/**
 * 一个第三方应用在宿主里的那一格 —— **一个 iframe 加一条桥**。
 *
 * <h2>为什么把它从 SurfaceHost 里拆出来</h2>
 *
 * 拆之前, REMOTE 那一支是一行 `<iframe>`。加桥之后它要做四件有状态的事: 持有 ref、
 * 在挂载时接桥、在卸载时拆桥、以及等应用报到之后收掉加载态。这四件事都是副作用, 而
 * `SurfaceHost` 是一个**纯渲染**的组件(它的 15 条断言全部靠 `renderToStaticMarkup`,
 * 副作用一条都不会跑)。把副作用塞进一个被那样断言的组件里, 结果只有两种: 断言开始
 * 骗人, 或者没人敢改它。
 *
 * <h2>没有宿主时它就是一个 iframe</h2>
 *
 * `useHostRuntime()` 返回 null(应用详情页里预览、静态渲染)时, 这里渲染的是**与加桥之前
 * 逐字节相同**的那个 iframe: 同样的 testid、同样的 sandbox、同样的 referrerPolicy。
 * 应用照常显示, 只是叫不动浮窗、也分享不出去 —— 而那两种场景本来就没有"用户邀请某个人"
 * 这回事。
 *
 * <h2>为什么加载态要等 `app.ready`</h2>
 *
 * iframe 的 load 事件说的是"HTML 到了", 不是"能用了" —— 一个要先登录、先连 WebSocket
 * 的应用在这两者之间会有一段空白。`app.ready` 是应用自己说的"我画好了", 所以它才是收掉
 * 那层纱的判据。没有宿主(因此永远收不到 ready)时不放大纱: 一个永远盖着的加载态比没有
 * 加载态糟得多。
 */
export interface ApplicationFrameProps {
  applicationId: string
  sessionId?: string
  surface: SurfaceType
  /** 应用名 —— 只用于 iframe 的 `title`, 也就是读屏与多标签时的那个名字。 */
  title?: string
  entry: string
}

export default function ApplicationFrame({
  applicationId,
  sessionId,
  surface,
  title,
  entry,
}: ApplicationFrameProps) {
  const runtime = useHostRuntime()
  const frameRef = useRef<HTMLIFrameElement>(null)
  const [loaded, setLoaded] = useState(false)

  const session = sessionId ?? ''
  const ready = runtime ? runtime.isReady(session) : true
  // `typeof window` 这一句是给静态渲染用的: 这个组件被 `SurfaceHost` 渲染, 而后者有一整套
  // `renderToStaticMarkup` 断言。非浏览器环境下 `hostOrigin` 为空, 判定直接通过 ——
  // 见 `isCrossOrigin` 的第二段。
  const hostOrigin = typeof window === 'undefined' ? '' : window.location.origin

  /**
   * 这一格占多高。
   *
   * <h2>整页的用视口高, 别的用容器高</h2>
   *
   * `h-full` 在这里**不可靠**: 它的百分比是相对父元素算的, 而父元素(`Frame` 里那层
   * `flex-1`)的高度来自 flex 布局, 不是一条显式的高度 —— 于是浏览器按"父高度不确定"
   * 处理, `h-full` 静默变成 `auto`, 整个 iframe 塌回 `min-h-[24rem]`(384px)。
   * 症状是应用只占了屏幕上半截, 下面一大片空白, 而应用里的按钮全在框外。
   *
   * 整页那一支的正确答案本来就是"占满视口" —— 这正是 §6 里 FULL_PAGE 的定义, 也是
   * "小程序拥有整个屏幕"的实现。别的四种容器是真的被摆在别人的地方里, 那里 `h-full`
   * 由外层盒子的显式高度撑着, 而 `min-h-[24rem]` 是它们的下限。
   */
  const box = surface === 'FULL_PAGE' ? 'h-[100dvh]' : 'h-full min-h-[24rem]'

  useEffect(() => {
    const frame = frameRef.current
    // 没有宿主, 或者应用还没说自己是谁(没有 sessionId 就没有会话上下文, 桥无从回答
    // `session.context`)—— 两种情况下都只渲染那个 iframe。
    if (!runtime || !frame || !session) return

    return runtime.attach(frame, { applicationId, sessionId: session, surface })
  }, [runtime, applicationId, session, surface])

  // 同源的 REMOTE 应用**不加载** —— 见 `isCrossOrigin` 的类注释: 那种情况下 iframe 上
  // 那层沙箱不成立, 而沙箱不成立时这里整套权限模型也就没有意义了。宁可拒绝并说清原因,
  // 也不要"先跑起来再说": 一个同源应用一旦被加载, 它拿到的不是多一点权限, 而是全部。
  if (!isCrossOrigin(entry, hostOrigin)) {
    return (
      <div
        className="rounded border border-line bg-sunken/40 p-4 text-sm text-ink-soft"
        data-testid="application-frame-same-origin"
      >
        <div className="text-ink">这个应用与聊天平台同源, 已拒绝加载</div>
        <p className="mt-1">
          第三方应用的入口必须放在它自己的域名下。同源会让 iframe 的沙箱失去作用 ——
          应用能直接读到本页的内容, 平台也就无法再限制它能做什么。这份清单里的 entry 是
          &quot;{entry}&quot;。
        </p>
      </div>
    )
  }

  return (
    <div className={`relative w-full ${box}`} data-testid="application-frame">
      <iframe
        ref={frameRef}
        data-testid="surface-remote-frame"
        title={title ?? applicationId}
        src={entry}
        onLoad={() => setLoaded(true)}
        className={`w-full rounded border border-line bg-surface ${box}`}
        // 第三方页面拿不到本页的 window 引用, 也带不走 referrer —— 它只该通过
        // 平台的动作接口做事, 而不是从 DOM 里够到什么。
        sandbox="allow-scripts allow-forms allow-same-origin allow-popups"
        referrerPolicy="no-referrer"
      />

      {/* 加载纱只在"有宿主、且应用还没报到"时出现 —— 见类注释最后一段。 */}
      {runtime && loaded && !ready && (
        <div
          className="pointer-events-none absolute inset-0 grid place-items-center bg-surface/70"
          data-testid="application-frame-loading"
        >
          <span className="text-xs text-ink-faint">应用启动中…</span>
        </div>
      )}
    </div>
  )
}
