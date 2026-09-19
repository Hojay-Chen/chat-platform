import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, Boxes, ChevronDown, Play, ShieldAlert, Zap } from 'lucide-react'
import {
  lap,
  describeLapError,
  type ActionSpec,
  type ApplicationDetail as Detail,
} from '@/api/lap'
import { PanelError, Skeleton } from '@/components/agent/PanelState'
import { Hint } from '@/components/ui/Hint'
import { permissionLevelZh, riskLevelZh, surfaceTypeZh, uiModeZh } from '@/lib/agentLabels'

/**
 * 「应用详情」—— 在点"打开"**之前**把该说的都说了。
 *
 * 这一页最重要的一件事是: <b>它在下架之后照样打得开。</b>
 * 详情接口刻意不按"在架"过滤成 404(见 `LapDiscoveryController.application`), 因为一个
 * 被挂起的应用需要能说出一句"它已下架", 而 404 只会让人以为是自己把 id 打错了。
 * 于是这一页读 `availability` 的三列, 决定"打开"这个按钮是亮的、灰的、还是根本不该出现。
 *
 * <h2>三个布尔各说一句话</h2>
 * <pre>
 *   inMarket               要不要出现在市场里 (这一页不关心, 市场页才关心)
 *   allowsNewSession       能不能开一局新的   → 决定"打开"按钮
 *   allowsExistingSession  手上那局还能不能下 → 决定那句"你已有的对局不受影响"
 * </pre>
 * 后端把三列都给了, 前端就<b>不该</b>自己去算 —— 一旦这里写一句
 * `status === 'SUSPENDED' || status === 'DEPRECATED'`, §4.1 那张表就有了第二份实现。
 *
 * <h2>这一页分成"给用户的两段"与"给开发者的一段"</h2>
 *
 * 它原先是一张清单直译: `状态 / 出现在市场 / 允许新会话 / 允许已有会话` 四行原始值,
 * 再一段 `模式 / 入口 / 最低客户端 / 支持的容器`, 再一列动作 id 配 `EXECUTE · 风险 3`。
 * 这些值一个都没有错, 但它们混在中文句子中间连成了一片 —— 一句话里两种语言、两行里
 * 三种抽象层级, 读的人要先翻译再判断。用户的原话是这几屏「制作的太烂了」。
 *
 * 现在分两段:
 * <pre>
 *   能不能用   三个布尔译成人话, 是这一页真正要回答的问题
 *   开发者信息 十态原值、entry 模板、五种容器、动作 id —— 折起来, 默认不展开
 * </pre>
 * 折起来不是删掉: 它们是应用作者与排查问题的人要的东西, 只是不该挡在用户前面。
 */
export default function ApplicationDetail() {
  const { applicationId = '' } = useParams()
  const navigate = useNavigate()

  const [detail, setDetail] = useState<Detail | null>(null)
  const [actions, setActions] = useState<ActionSpec[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [devOpen, setDevOpen] = useState(false)

  const report = useCallback((e: unknown) => setError(describeLapError(e)), [])

  useEffect(() => {
    Promise.all([lap.application(applicationId), lap.actionsOf(applicationId)])
      .then(([d, a]) => {
        setDetail(d)
        setActions(a)
      })
      .catch(report)
  }, [applicationId, report])

  const open = async () => {
    setBusy(true)
    setError(null)
    try {
      const session = await lap.openSession(applicationId)
      navigate(`/applications/${encodeURIComponent(applicationId)}/sessions/${session.sessionId}`)
    } catch (e) {
      // APPLICATION_NOT_AVAILABLE 会走到这里 —— 直接把它显示出来。
      // "为什么打不开"的判据在服务端, 这一页的职责是转述, 不是重新判断。
      report(e)
    } finally {
      setBusy(false)
    }
  }

  const availability = detail?.availability
  const canOpen = availability?.allowsNewSession ?? false

  return (
    /*
     * `h-full overflow-y-auto` 而不是 `min-h-screen` —— 这一页是 FullScreenLayout 的
     * 子路由, 而那个布局是 `h-dvh overflow-hidden`(它把纵向空间交给页面自己管)。
     * 页面用 `min-h-screen` 又不给自己一个滚动容器, 后果是**超出首屏的内容被裁掉且
     * 滚不到** —— 而这一页的内容恰恰是"编译出人格之后才长出来"的, 首屏一定装不下。
     *
     * 滚动容器放在根节点上, 上面那个 `sticky top-0` 的头部才有东西可吸; 之前
     * `overflow-hidden` 的父级让 sticky 无处可吸, 头部实际是死的。
     */
    <div className="h-full overflow-y-auto bg-surface">
      <header className="sticky top-0 z-10 border-b border-line bg-surface/80 backdrop-blur-md">
        <div className="mx-auto flex max-w-4xl items-center gap-3 px-5 py-3.5">
          <Link
            to="/discover"
            className="btn-ghost shrink-0 !px-3 !py-1.5"
            title="回到发现"
            aria-label="回到发现"
          >
            <ArrowLeft size={15} />
          </Link>
          <Boxes className="shrink-0 text-accent" size={20} />
          <span className="min-w-0 flex-1 truncate text-lg font-medium tracking-tight text-ink">
            {detail?.name || applicationId}
          </span>
        </div>
      </header>

      <main className="mx-auto max-w-4xl px-5 py-8">
        {error && (
          <div className="mb-6">
            <PanelError message={error} />
          </div>
        )}

        {!detail && !error && <DetailSkeleton />}

        {detail && (
          <>
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div className="min-w-0">
                <h1 className="page-title">{detail.name || detail.applicationId}</h1>
                {/* 应用 id 与版本号是技术标识 —— 等宽, 让它们看起来就是个 id 而不是标题 */}
                <p className="mt-1 font-mono text-[11px] text-ink-faint">
                  {detail.applicationId}
                  {detail.version ? ` · v${detail.version}` : ''}
                  {detail.category ? ` · ${detail.category}` : ''}
                </p>
              </div>
              <button
                type="button"
                onClick={open}
                disabled={busy || !canOpen}
                className="btn-primary shrink-0 disabled:opacity-40"
              >
                <Play size={14} />
                {busy ? '正在打开…' : '打开'}
              </button>
            </div>

            {detail.description && (
              <p className="mt-4 max-w-2xl text-sm leading-relaxed text-ink-soft">
                {detail.description}
              </p>
            )}

            {/* 能不能用 —— §4.1 那张表在这一页上的样子, 译成人话 */}
            <section className="mt-8">
              <h2 className="text-sm font-medium text-ink">现在能不能用</h2>
              <div className="card mt-3 divide-y divide-line">
                <Fact
                  label="能不能开新的一局"
                  yes={availability?.allowsNewSession}
                  yesText="可以"
                  noText="现在不行"
                />
                <Fact
                  label="已有的那一局还能不能继续"
                  yes={availability?.allowsExistingSession}
                  yesText="可以, 不受影响"
                  noText="也不能了"
                />
                <Fact
                  label="会不会出现在「发现」里"
                  yes={availability?.inMarket}
                  yesText="会"
                  noText="不会, 已经收起来了"
                />
              </div>

              {!canOpen && (
                <p className="mt-3 flex items-start gap-2 text-xs leading-relaxed text-warn">
                  <ShieldAlert size={14} className="mt-0.5 shrink-0" />
                  <span>
                    这个应用当前开不了新的会话。
                    {availability?.allowsExistingSession
                      ? '但已经在进行的对局不受影响 —— 下架不等于作废。'
                      : '已有的会话也一并不可用。'}
                  </span>
                </p>
              )}
            </section>

            {/* 动作 —— 界面之外的那一半: 数字人用的是同一份清单 */}
            <section className="mt-8">
              <h2 className="flex items-center gap-2 text-sm font-medium text-ink">
                它能做什么
                <span className="tnum text-ink-faint">({actions.length})</span>
                <Hint label="什么是动作">
                  动作是这个应用能被<strong>点</strong>的那部分 —— 界面上按一个按钮、
                  或者一个数字人自己去调它, 走的是同一份清单、同一套权限。所以这里
                  列出来的, 就是它能对这个世界做的事。
                </Hint>
              </h2>
              <div className="mt-3 space-y-2">
                {actions.map((spec) => (
                  <ActionRow key={spec.actionId} spec={spec} />
                ))}
                {actions.length === 0 && (
                  <p className="rounded-lg bg-sunken px-4 py-3 text-xs leading-relaxed text-ink-faint">
                    这个应用还没有发布任何动作 —— 也就是说它现在只是一块界面,
                    点不动外面的任何东西。
                  </p>
                )}
              </div>
            </section>

            {/* 开发者信息 —— 折起来。见文件头。 */}
            <section className="mt-8">
              <button
                type="button"
                onClick={() => setDevOpen((v) => !v)}
                aria-expanded={devOpen}
                className="flex w-full items-center gap-2 rounded-lg px-1 py-1.5 text-left text-sm font-medium text-ink-soft transition-colors hover:text-ink focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/40"
              >
                <ChevronDown
                  size={15}
                  className={`shrink-0 transition-transform ${devOpen ? '' : '-rotate-90'}`}
                />
                开发者信息
                <span className="text-xs font-normal text-ink-faint">
                  清单里的原值, 排查问题时看
                </span>
              </button>

              {devOpen && (
                <div className="mt-2 space-y-3">
                  <div className="card divide-y divide-line">
                    <Fact
                      label="状态"
                      raw={detail.status ?? '—'}
                      hint="十态原值。界面上只说「能不能开」, 这个值给的是「为什么」。"
                    />
                    <Fact label="界面模式" raw={uiModeZh(detail.ui.type)} code={detail.ui.type} />
                    <Fact label="入口" raw={detail.ui.entry} mono />
                    <Fact label="最低客户端" raw={detail.ui.minClientVersion ?? '不限'} mono />
                  </div>

                  <div className="card">
                    <div className="text-xs text-ink-faint">这个应用准备了几种打开方式</div>
                    <div className="mt-2 flex flex-wrap gap-1.5">
                      {detail.ui.surfaces.map((s) => (
                        <span
                          key={s.type}
                          title={`${s.type} · ${s.entry}`}
                          className="chip border border-line bg-sunken text-ink-soft"
                        >
                          {surfaceTypeZh(s.type)}
                          <span className="font-mono text-[10px] text-ink-faint">{s.type}</span>
                        </span>
                      ))}
                      {detail.ui.surfaces.length === 0 && (
                        <span className="text-xs text-ink-faint">
                          一种都没有声明 —— 平台上它只能整页打开。
                        </span>
                      )}
                    </div>
                  </div>
                </div>
              )}
            </section>
          </>
        )}
      </main>
    </div>
  )
}

/**
 * 一行"能 / 不能"。
 *
 * 三态: 值还没拿到(`undefined`)时**不说"否"** —— 那会把"还没读到"说成一个结论,
 * 而这两个状态的下一步恰好相反(一个该等, 一个该另想办法)。
 */
function Fact({
  label,
  yes,
  yesText = '是',
  noText = '否',
  raw,
  code,
  mono,
  hint,
}: {
  label: string
  yes?: boolean
  yesText?: string
  noText?: string
  /** 直接给原值, 不走 是/否 —— 状态、入口这些字段就是原值 */
  raw?: string
  /** 原值的中文之外, 旁边再挂一个等宽的枚举原文 */
  code?: string
  mono?: boolean
  hint?: string
}) {
  const pending = yes === undefined
  const text = raw ?? (pending ? '—' : yes ? yesText : noText)
  const tone = raw !== undefined || pending ? 'text-ink-soft' : yes ? 'text-ok' : 'text-warn'

  return (
    <div className="flex items-baseline justify-between gap-4 px-4 py-3">
      <span className="flex items-center gap-1.5 text-sm text-ink-soft">
        {label}
        {hint && (
          <Hint label={`关于${label}`} align="start">
            {hint}
          </Hint>
        )}
      </span>
      <span className="flex min-w-0 items-baseline gap-2">
        <span
          className={`truncate text-sm ${tone} ${mono ? 'font-mono text-[12px]' : ''}`}
          title={text}
        >
          {text}
        </span>
        {code && code !== text && (
          <span className="shrink-0 font-mono text-[10px] text-ink-faint">{code}</span>
        )}
      </span>
    </div>
  )
}

/**
 * 一个动作。
 *
 * 主词是**描述**(人能读的那句), 而不是 `actionId` —— 原来整列都是
 * `game.make_move` 这种东西, 读的人得先在心里把它翻成"下一步棋"。id 退到下面,
 * 等宽, 因为它确实是给机器的。
 */
function ActionRow({ spec }: { spec: ActionSpec }) {
  const risk = riskLevelZh(spec.riskLevel)
  const risky = ['HIGH', 'CRITICAL'].includes(String(spec.riskLevel ?? '').toUpperCase())

  return (
    <div className="rounded-xl border border-line bg-raised px-4 py-3">
      <div className="flex items-start gap-2.5">
        <span className="mt-0.5 shrink-0 rounded-lg bg-accent-soft p-1.5 text-accent">
          <Zap size={14} />
        </span>
        <div className="min-w-0 flex-1">
          <div className="text-sm text-ink">
            {spec.description || <span className="font-mono text-[13px]">{spec.actionId}</span>}
          </div>
          <div className="mt-1 flex flex-wrap items-center gap-1.5">
            {spec.description && (
              <span className="font-mono text-[11px] text-ink-faint">{spec.actionId}</span>
            )}
            {spec.permissionLevel && (
              <span className="chip bg-sunken text-ink-soft">
                {permissionLevelZh(spec.permissionLevel)}
              </span>
            )}
            {risk && (
              <span
                className={`chip ${risky ? 'bg-danger/10 text-danger' : 'bg-sunken text-ink-soft'}`}
              >
                风险{risk}
              </span>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

/** 首屏骨架 —— 按下面真实布局的形状摆, 到位时不算跳。 */
function DetailSkeleton() {
  return (
    <div className="space-y-8" aria-busy aria-label="正在加载应用详情">
      <div className="space-y-3">
        <Skeleton className="h-7 w-2/5" />
        <Skeleton className="h-3 w-1/3" />
        <Skeleton className="h-3 w-4/5" />
      </div>
      <div className="space-y-3">
        <Skeleton className="h-3.5 w-24" />
        <div className="card space-y-3 p-4">
          <Skeleton className="h-3 w-full" />
          <Skeleton className="h-3 w-4/5" />
          <Skeleton className="h-3 w-3/5" />
        </div>
      </div>
      <div className="space-y-3">
        <Skeleton className="h-3.5 w-20" />
        <Skeleton className="h-14 w-full rounded-xl" />
      </div>
    </div>
  )
}
