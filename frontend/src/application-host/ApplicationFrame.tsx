import { useEffect, useRef, useState } from 'react'
import { ShieldAlert } from 'lucide-react'
import type { SurfaceType } from '@/api/lap'
import { Skeleton } from '@/components/agent/PanelState'
import { Hint } from '@/components/ui/Hint'
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
  /** 换掉 iframe 元素用 —— 见下面那个 `key` 与「重新加载」。 */
  const [reloadKey, setReloadKey] = useState(0)

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

  /**
   * 那一圈发丝边框 —— **整页时不要**。
   *
   * <h2>为什么整页的那一支不能有边框</h2>
   *
   * 另外四种 Surface 里, 应用是被平台摆在"一块地方"上的, 1px 边框在说"到这儿为止"。
   * 整页不是: 应用就是这一屏(见 `SurfaceHost` 里 FULL_PAGE 的定义)。给它框一圈, 等于
   * 把应用重新降格成"网页里的一块", 而且那圈框的四条边里有两条(左、右)会正落在屏幕
   * 两侧 —— 挨着 `AppSession` 那条列的两侧, 于是同一个位置叠出两条线。
   *
   * 用户报的那条「最右边还有分层竖线」, 一条来自 `AppSession` 的列边框(已删), 另一条
   * 就是它。两条同色的 1px 竖线紧挨着, 看着就是"分层"的。
   */
  const chrome = surface === 'FULL_PAGE' ? '' : 'rounded border border-line'

  useEffect(() => {
    const frame = frameRef.current
    // 没有宿主, 或者应用还没说自己是谁(没有 sessionId 就没有会话上下文, 桥无从回答
    // `session.context`)—— 两种情况下都只渲染那个 iframe。
    if (!runtime || !frame || !session) return

    return runtime.attach(frame, { applicationId, sessionId: session, surface })
    // `reloadKey` 必须在这里: 「重新加载」会把 iframe **整个换掉**(见下面那个 key),
    // 于是这一支要重新接一次桥 —— 少了它, 重挂之后所有回执都发给一个已经不存在的窗口,
    // 而症状是"点了重新加载之后应用彻底不动了"。
  }, [runtime, applicationId, session, surface, reloadKey])

  // 同源的 REMOTE 应用**不加载** —— 见 `isCrossOrigin` 的类注释: 那种情况下 iframe 上
  // 那层沙箱不成立, 而沙箱不成立时这里整套权限模型也就没有意义了。宁可拒绝并说清原因,
  // 也不要"先跑起来再说": 一个同源应用一旦被加载, 它拿到的不是多一点权限, 而是全部。
  if (!isCrossOrigin(entry, hostOrigin)) {
    return (
      <div
        className="rounded-lg border border-danger/30 bg-danger/10 px-4 py-3 text-sm"
        data-testid="application-frame-same-origin"
      >
        <div className="flex items-center gap-2 text-ink">
          <ShieldAlert size={15} className="shrink-0 text-danger" />
          <span>这个应用装不进来, 已拒绝加载</span>
          {/*
            为什么拒绝 —— 这是"理解它为什么这样"才需要的一段, 所以它在 `?` 里,
            不在主干上(见 Hint 的类注释)。留在屏上的那句只说"现在能拿它怎么办"。
          */}
          <Hint label="为什么拒绝加载">
            第三方应用的入口必须放在它自己的域名下。如果它与聊天平台同源, iframe 的沙箱
            就失去作用 —— 应用能直接读到本页的内容, 平台也就无法再限制它能做什么。
            这份清单里的入口是 &quot;{entry}&quot;, 与本站同源。
          </Hint>
        </div>
        <p className="mt-1 text-xs text-ink-soft">
          这是应用清单里的入口写错了。把它改到应用自己的域名上, 重新发布之后就能打开。
        </p>
      </div>
    )
  }

  return (
    <div className={`relative w-full ${box}`} data-testid="application-frame">
      <iframe
        // 换掉整个 iframe 元素 = 重新加载。跨域时拿不到 `contentWindow.location`,
        // 所以这是唯一一条真的能重来的路。
        key={reloadKey}
        ref={frameRef}
        data-testid="surface-remote-frame"
        title={title ?? applicationId}
        src={entry}
        onLoad={() => setLoaded(true)}
        className={`w-full bg-surface ${chrome} ${box}`}
        // 第三方页面拿不到本页的 window 引用, 也带不走 referrer —— 它只该通过
        // 平台的动作接口做事, 而不是从 DOM 里够到什么。
        sandbox="allow-scripts allow-forms allow-same-origin allow-popups"
        referrerPolicy="no-referrer"
      />

      {/*
        加载纱只在"有宿主、且应用还没报到"时出现 —— 见类注释最后一段。

        它原先是一句居中的「应用启动中…」。改成骨架是因为那句话什么形状都不给:
        应用报到的瞬间, 屏幕上会整块换掉。骨架按"一个应用大概长什么样"摆(顶上一栏、
        下面几块内容), 换掉的时候就不算跳。

        猜不到这个应用真实的布局 —— 一个 iframe 宿主不可能知道 —— 所以这里只摆最
        一般的那种, 不假装知道更多。
      */}
      {runtime && loaded && !ready && (
        <div
          className="pointer-events-none absolute inset-0 flex flex-col bg-surface/85"
          data-testid="application-frame-loading"
        >
          <div className="flex items-center gap-3 px-4 py-3">
            <Skeleton className="h-8 w-8 shrink-0 rounded-lg" />
            <Skeleton className="h-3.5 w-28" />
            <Skeleton className="ml-auto h-6 w-16 shrink-0 rounded-full" />
          </div>
          <div className="min-h-0 flex-1 space-y-3 px-4 py-2">
            <Skeleton className="h-3 w-3/5" />
            <Skeleton className="h-3 w-4/5" />
            <Skeleton className="h-3 w-2/5" />
          </div>
          {/*
            一条出路。加载纱的判据是应用自己报到(`app.ready`), 而一个不报到的应用会让
            它永远盖着 —— 一个永远盖着的加载态比没有加载态糟得多(见类注释)。所以这里
            给一个重来的按钮, 而不是让用户只能刷新整页。
          */}
          <div className="pointer-events-auto flex items-center justify-center gap-2 pb-5 text-xs text-ink-faint">
            <span>应用正在启动</span>
            <button
              type="button"
              onClick={() => {
                setLoaded(false)
                setReloadKey((k) => k + 1)
              }}
              className="rounded underline underline-offset-2 transition-colors hover:text-ink focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/40"
            >
              加载不出来? 重新加载
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
