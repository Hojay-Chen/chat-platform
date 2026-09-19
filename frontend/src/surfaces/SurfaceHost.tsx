import type { ReactNode } from 'react'
import {
  AlertTriangle,
  ArrowUpRight,
  Blocks,
  FileWarning,
  Maximize2,
  MonitorSmartphone,
  X,
} from 'lucide-react'
import type { SurfaceType, UiView } from '@/api/lap'
import ApplicationFrame from '@/application-host/ApplicationFrame'
import { Hint } from '@/components/ui/Hint'
import { CLIENT_VERSION, clientSupports, embeddedAppOf } from './registry'
import { isAbsoluteHttpUrl, planSurface, resolveEntry } from './entry'

/**
 * SurfaceHost —— 把<b>同一份应用界面</b>摆到五种地方。
 *
 * <h2>这个组件为什么存在</h2>
 * §67 说同一个 Application 应当能"聊天里打开、独立页面打开、侧边栏打开", 而 §17/§69 说平台
 * 不该定义 UI 怎么画。两句话合起来只有一种实现: 呈现方式归平台, 界面内容归应用。
 * SurfaceHost 就是这两者的接缝 —— 它决定外框(整页 / 卡片 / 浮层 / 抽屉 / 一行),
 * 应用组件只管画自己的东西, 不必知道自己在哪种框里。
 *
 * <h2>五种 Surface 是真的五种行为, 不是五个 CSS 类</h2>
 * 它们的差别是**交互契约**上的, 所以能各自被断言:
 *
 * <pre>
 *   FULL_PAGE  铺满, 自己就是页面        —— 有返回
 *   EMBEDDED   嵌在别人页面里的一块      —— 无返回(关掉它不归它管)
 *   MODAL      盖住当前页的浮层          —— 有遮罩, 点遮罩/× 关闭, Esc 关闭
 *   PANEL      从边上滑出的抽屉          —— 有 ×, 但不吃 Esc(用户可能在看背后的页面)
 *   INLINE     一行, 可升级为整页        —— 有"放大"
 * </pre>
 *
 * <h2>三种 ui.type 是这个接缝的另一半</h2>
 * {@code EMBEDDED} 走平台内的应用登记表(内置应用); {@code REMOTE} 走 iframe(第三方);
 * {@code NATIVE} 是移动端/桌面端的事, 网页版只能给出深链接并说明。
 *
 * <h2>它<b>不</b>做的事</h2>
 * 不解析 entry 的内容(只填两个变量, 见 {@link resolveEntry})、不认识应用的动作、不替应用存状态。
 * 一条 entry 里如果出现第三个变量, 它会原样留在字符串里 —— 那是作者与平台之间的分歧,
 * 把它抹平成空白只会让分歧更晚被发现。
 */
export interface SurfaceHostProps {
  applicationId: string
  /** 还没有会话时(例如应用详情页)可以为空 —— 此时 `{sessionId}` 不会被填进去。 */
  sessionId?: string
  ui: UiView
  surface?: SurfaceType
  /** 应用名 —— 只用于外框上那行标题。 */
  title?: string
  /** 应用自己没提供内置界面时的兜底内容(通常是一份动作清单)。 */
  fallback?: ReactNode
  /** MODAL 的关闭回调。缺省时按"这是无人值守的嵌入"处理, 不显示关闭按钮。 */
  onClose?: () => void
  onExpand?: () => void
  /**
   * 让应用**自己拥有整个视口**, 而不是被摆在一张有内边距的纸中间。
   *
   * <h2>为什么这是 FULL_PAGE 专属的一个开关, 而不是把 p-4 删掉</h2>
   *
   * `p-4` 对"应用详情页里预览一下这个应用"是对的 —— 那块地方不属于应用, 留白是
   * 在说"这是嵌进来的"。但小程序宿主(用户从「发现」点进来)是另一回事: 那一刻应用
   * 就是这一屏, 四周那圈 16px 会立刻把它降格成"一个网页里的一块"。
   *
   * 两种情况都需要, 所以它是一个开关而不是一次改判。**默认 false** —— 缺省行为与
   * 加这个字段之前逐字节相同, `SurfaceHost.test.tsx` 的 15 条断言一条都不动。
   */
  bleed?: boolean
  className?: string
}

export default function SurfaceHost({
  applicationId,
  sessionId,
  ui,
  surface = 'FULL_PAGE',
  title,
  fallback,
  onClose,
  onExpand,
  bleed = false,
  className,
}: SurfaceHostProps) {
  // 1. 客户端版本 —— 比不过就拒绝渲染, 并说清是哪一边旧
  if (!clientSupports(ui.minClientVersion)) {
    return (
      <Frame surface={surface} className={className} bleed={bleed} onClose={onClose} testId="surface-host">
        <Notice
          tone="warn"
          icon={<AlertTriangle size={15} />}
          title="需要更新的客户端"
          body={`它要求客户端 ${ui.minClientVersion}, 当前是 ${CLIENT_VERSION}。`}
          hint={
            <>
              「最低客户端版本」是应用作者写的"这份界面至少要哪个版本的客户端才看得懂"。
              版本比不过时宁可拒绝渲染, 也不硬着头皮画一个自己看不懂的入口模板 —— 后者在白屏那里
              才暴露, 而白屏什么信息都不给人留下。
            </>
          }
        />
      </Frame>
    )
  }

  // 2. Surface 选择 —— 没声明就退到整页, 但页面能看出退过档
  const plan = planSurface(ui, surface)
  const entry = resolveEntry(plan.template, { applicationId, sessionId })
  const inner = renderContent()

  return (
    <Frame
      surface={plan.surface}
      className={className}
      bleed={bleed}
      onClose={onClose}
      onExpand={onExpand}
      title={title}
      testId="surface-host"
      dataSurface={plan.surface}
      dataRequested={plan.requested}
      dataFallback={plan.fallback ? 'true' : 'false'}
      dataEntry={entry}
    >
      {plan.fallback && (
        <p className="mb-2 text-xs text-ink-faint" data-testid="surface-fallback-note">
          这个应用没有准备「{surfaceName(plan.requested)}」这种打开方式, 已按
          「{surfaceName(plan.surface)}」打开。
        </p>
      )}
      {inner}
    </Frame>
  )

  function renderContent(): ReactNode {
    switch (ui.type) {
      case 'EMBEDDED': {
        const App = embeddedAppOf(applicationId)
        if (!App) {
          // 平台没有这个应用的界面实现 —— 这不是错误, 只是"它没做界面"。
          // 给一条能走下去的路(动作清单), 而不是一句"暂不支持"。
          return (
            <div data-testid="surface-unregistered">
              <Notice
                icon={<Blocks size={15} />}
                title="这个应用没有自带的界面"
                body={`平台里没有为 ${applicationId} 画界面的实现, 但它的动作照样能点 —— 下面这些就是。`}
              />
              {fallback}
            </div>
          )
        }
        return (
          <div data-testid="surface-embedded-app">
            <App applicationId={applicationId} sessionId={sessionId ?? ''} surface={plan.surface} />
          </div>
        )
      }

      case 'REMOTE': {
        if (!isAbsoluteHttpUrl(entry)) {
          return (
            <div data-testid="surface-remote-invalid">
              <Notice
                tone="danger"
                icon={<FileWarning size={15} />}
                title="这个应用的入口写错了, 已拒绝加载"
                body={`入口必须是一条完整的 http(s) 地址, 清单里写的是 "${entry}"。`}
                hint={
                  <>
                    这条入口会被放进承载第三方应用的那个 iframe 的 src。写成相对路径的话,
                    浏览器会在**本站**里找这个页面 —— 结果是一个 404 的空框, 而且没人看得出
                    是哪一步错了。清单由应用作者发布, 改它要改应用那一侧。
                  </>
                }
              />
            </div>
          )
        }
        // 第三方应用走 `ApplicationFrame` —— 它比一行 iframe 多了三件事: 挂桥、卸载时
        // 拆桥、以及等应用报到之后收掉加载态(§15)。这一层里**没有**宿主时它会退回成
        // 与加桥之前逐字节相同的那个 iframe, 所以应用详情页的预览不受影响。
        return (
          <ApplicationFrame
            applicationId={applicationId}
            sessionId={sessionId}
            surface={plan.surface}
            title={title}
            entry={entry}
          />
        )
      }

      case 'NATIVE':
        return (
          <div data-testid="surface-native">
            <Notice
              icon={<MonitorSmartphone size={15} />}
              title="手机里打开这个应用, 网页版打不开"
              body="在支持这种客户端的设备上用它, 网页版只能把入口给到你。"
            />
            {/*
              深链接原样摆在下面 —— 它是一个能复制走的东西, 而不是要读的一句话。
              等宽 + 可选中: 手抄一串 url 是最容易抄错的一类操作。
            */}
            <p className="mt-2 flex select-all items-center gap-1.5 rounded-lg bg-sunken px-3 py-2 font-mono text-[11px] text-ink-soft">
              <ArrowUpRight size={12} className="shrink-0 text-ink-faint" />
              <span className="min-w-0 break-all">{entry}</span>
            </p>
          </div>
        )

      default:
        return (
          <div data-testid="surface-unknown-mode">
            <Notice
              tone="warn"
              icon={<AlertTriangle size={15} />}
              title="认不出这是哪种打开方式"
              body={`平台不认识 ui.type = ${String(ui.type)}, 所以不知道该把它摆成什么。`}
            />
          </div>
        )
    }
  }
}

/**
 * Surface 类型的中文名。
 *
 * 用户看到的那句话里不该出现 `FULL_PAGE` / `PANEL` 这种值 —— 它们是清单里的**枚举**,
 * 是写给平台读的。应用作者在开发者那一屏看的是原值, 而用户读到的是"侧边栏"。
 */
const SURFACE_NAMES: Record<SurfaceType, string> = {
  FULL_PAGE: '整页',
  EMBEDDED: '嵌在页面里',
  MODAL: '弹出窗口',
  PANEL: '侧边栏',
  INLINE: '一行',
}

function surfaceName(type: SurfaceType): string {
  return SURFACE_NAMES[type] ?? String(type)
}

// ─────────────────────────── 五种外框 ───────────────────────────

interface FrameProps {
  surface: SurfaceType
  title?: string
  children: ReactNode
  className?: string
  bleed?: boolean
  onClose?: () => void
  onExpand?: () => void
  testId?: string
  dataSurface?: string
  dataRequested?: string
  dataFallback?: string
  dataEntry?: string
}

function Frame({
  surface,
  title,
  children,
  className,
  bleed = false,
  onClose,
  onExpand,
  testId,
  dataSurface,
  dataRequested,
  dataFallback,
  dataEntry,
}: FrameProps) {
  const attrs = {
    'data-testid': testId,
    'data-surface': dataSurface ?? surface,
    'data-requested': dataRequested,
    'data-fallback': dataFallback,
    'data-entry': dataEntry,
  }

  switch (surface) {
    case 'MODAL':
      return (
        <div
          {...attrs}
          className={`fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 ${className ?? ''}`}
        >
          <div className="flex max-h-[85vh] w-full max-w-2xl flex-col overflow-hidden rounded-xl border border-line bg-surface shadow-pop">
            <Chrome title={title} onClose={onClose} closeLabel="关闭" />
            <div className="overflow-auto p-4">{children}</div>
          </div>
        </div>
      )

    case 'PANEL':
      // `shadow-pop` 而不是 `shadow-2xl`: 全项目只有 `pop` 一个阴影 token(见
      // tailwind.config.js), 而 `shadow-2xl` 是 Tailwind 的默认值 —— 它绕过了那套
      // token, 于是面板的投影与浮窗、胶囊的投影不是同一种光。
      return (
        <div
          {...attrs}
          className={`fixed right-0 top-0 z-50 flex h-full w-full max-w-md flex-col border-l border-line bg-surface shadow-pop ${className ?? ''}`}
        >
          <Chrome title={title} onClose={onClose} closeLabel="收起" />
          <div className="overflow-auto p-4">{children}</div>
        </div>
      )

    case 'EMBEDDED':
      // 嵌在别人的页面里 —— 所以**没有**关闭按钮, 也**没有**标题栏:
      // 这块地方的主人不是我, 在我这块里放一个"关掉整个面板"的按钮是越权。
      //
      // `bg-raised` 而不是 `bg-surface/60`: 后者是"页面底色再透一点", 画在页面上
      // 等于没有底色 —— 一块既没有边界也没有面的地方读起来不像"一块地方"。
      return (
        <div {...attrs} className={`rounded-xl border border-line bg-raised p-4 ${className ?? ''}`}>
          {children}
        </div>
      )

    case 'INLINE':
      return (
        <div
          {...attrs}
          className={`flex items-center gap-3 rounded-lg border border-line bg-raised px-3 py-2 ${className ?? ''}`}
        >
          <div className="min-w-0 flex-1 truncate">{children}</div>
          {onExpand && (
            <button
              type="button"
              onClick={onExpand}
              className="btn-ghost shrink-0 !px-2 !py-1"
              data-testid="surface-inline-expand"
              title="展开为整页"
            >
              <Maximize2 size={14} />
            </button>
          )}
        </div>
      )

    case 'FULL_PAGE':
    default:
      return (
        <div {...attrs} className={`flex min-h-screen flex-col bg-surface ${className ?? ''}`}>
          {title || onClose ? <Chrome title={title} onClose={onClose} closeLabel="返回" /> : null}
          {/* 见 SurfaceHostProps.bleed —— 小程序宿主让应用自己拥有整个视口 */}
          <div className={bleed ? 'flex-1' : 'flex-1 p-4'}>{children}</div>
        </div>
      )
  }
}

function Chrome({
  title,
  onClose,
  closeLabel,
}: {
  title?: string
  onClose?: () => void
  closeLabel: string
}) {
  return (
    <div className="flex shrink-0 items-center justify-between gap-3 border-b border-line px-4 py-3">
      {/* 标题缺省时不留一条空栏 —— 没有 title 又给了 onClose 的调用点(容器预览里
          的几种)会得到一条只有关闭按钮的细栏, 而不是一条写着空白的粗栏。 */}
      <span className="min-w-0 flex-1 truncate text-sm font-medium text-ink">{title ?? ''}</span>
      {onClose && (
        <button
          type="button"
          onClick={onClose}
          className="btn-ghost shrink-0 !px-2 !py-1"
          title={closeLabel}
          aria-label={closeLabel}
          data-testid="surface-close"
        >
          <X size={15} />
        </button>
      )}
    </div>
  )
}

/**
 * 平台替应用说的一句话 —— "它没做界面"、"它的入口写错了"、"它要更新的客户端"。
 *
 * <h2>它原先的问题有三个, 都不是措辞问题</h2>
 *
 * 1. **图标是错的**: 五种完全不同的情形共用一个 `ExternalLink` —— 一个"版本不够"配
 *    一个"在新窗口打开"的图标, 读的人第一眼就被指向了错的地方。
 * 2. **没有轻重**: "这个应用没有自带界面"(正常, 往下走就行)与"入口写错了"(清单坏了)
 *    长得一模一样, 于是前者也被画成了坏消息。
 * 3. **正文是一段文档**: 讲的是"为什么 platform 这样设计", 而站在这一屏的人要问的是
 *    "那我现在能怎么办"。所以解释进了 `?`, 留在外面的是结论与下一步。
 */
function Notice({
  tone = 'info',
  icon,
  title,
  body,
  hint,
}: {
  tone?: 'info' | 'warn' | 'danger'
  icon: ReactNode
  title: string
  body: string
  /** 「为什么它这样」那一段 —— 见 `Hint` 的类注释。不给就不画那个 `?`。 */
  hint?: ReactNode
}) {
  const skin =
    tone === 'danger'
      ? 'border-danger/30 bg-danger/10'
      : tone === 'warn'
        ? 'border-warn/30 bg-warn/10'
        : 'border-line bg-sunken/50'
  const iconSkin =
    tone === 'danger' ? 'text-danger' : tone === 'warn' ? 'text-warn' : 'text-ink-faint'

  return (
    <div className={`rounded-xl border px-4 py-3.5 ${skin}`}>
      <div className="flex items-center gap-2">
        <span className={`shrink-0 ${iconSkin}`}>{icon}</span>
        <span className="min-w-0 flex-1 text-sm font-medium text-ink">{title}</span>
        {hint && (
          <Hint label="为什么会这样" align="end">
            {hint}
          </Hint>
        )}
      </div>
      <p className="mt-1.5 text-[13px] leading-relaxed text-ink-soft">{body}</p>
    </div>
  )
}
