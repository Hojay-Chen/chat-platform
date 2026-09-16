import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, Boxes, Play, ShieldAlert } from 'lucide-react'
import {
  lap,
  LapError,
  type ActionSpec,
  type ApplicationDetail as Detail,
} from '@/api/lap'

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
 */
export default function ApplicationDetail() {
  const { applicationId = '' } = useParams()
  const navigate = useNavigate()

  const [detail, setDetail] = useState<Detail | null>(null)
  const [actions, setActions] = useState<ActionSpec[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const report = useCallback((e: unknown) => {
    if (e instanceof LapError) setError(`${e.code} — ${e.message}`)
    else setError(e instanceof Error ? e.message : String(e))
  }, [])

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
        <div className="mx-auto flex max-w-4xl items-center gap-3 px-5 py-4">
          <Link to="/applications" className="btn-ghost !px-3 !py-1.5" title="回到应用市场">
            <ArrowLeft size={15} />
          </Link>
          <Boxes className="text-accent" size={20} />
          <span className="text-lg text-ink">
            {detail?.name || applicationId}
          </span>
        </div>
      </header>

      <main className="mx-auto max-w-4xl px-5 py-10">
        {error && (
          <div className="card border-danger/30 bg-danger/10 text-sm text-danger">{error}</div>
        )}

        {!detail && !error && <p className="text-sm text-ink-faint">正在加载…</p>}

        {detail && (
          <>
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div>
                <h1 className="page-title">{detail.name || detail.applicationId}</h1>
                <p className="mt-1 text-xs text-ink-faint">
                  {detail.applicationId} · v{detail.version}
                  {detail.category ? ` · ${detail.category}` : ''}
                </p>
              </div>
              <button
                type="button"
                onClick={open}
                disabled={busy || !canOpen}
                className="btn-primary disabled:opacity-40"
                title={canOpen ? '开一场新会话' : '这个应用当前不能开新会话'}
              >
                <Play size={14} />
                打开
              </button>
            </div>

            {detail.description && (
              <p className="mt-4 text-sm text-ink-soft">{detail.description}</p>
            )}

            {/* 可用性 —— §4.1 那张表在这一页上的样子 */}
            <section className="mt-8">
              <h2 className="text-sm font-medium text-ink-soft">可用性</h2>
              <div className="card mt-3 space-y-2 text-sm">
                <Row label="状态" value={detail.status ?? '—'} hint="十态原值, 开发者后台读的是它" />
                <Row
                  label="出现在市场"
                  value={availability?.inMarket ? '是' : '否'}
                  hint={availability?.state}
                />
                <Row label="允许新会话" value={availability?.allowsNewSession ? '是' : '否'} />
                <Row label="允许已有会话" value={availability?.allowsExistingSession ? '是' : '否'} />
              </div>

              {!canOpen && (
                <p className="mt-3 flex items-start gap-2 text-xs text-warn">
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

            {/* 界面 —— 平台只搬运 surface type / entry / 最低版本, 不解释它们 (§69) */}
            <section className="mt-8">
              <h2 className="text-sm font-medium text-ink-soft">界面</h2>
              <div className="card mt-3 space-y-2 text-sm">
                <Row label="模式" value={detail.ui.type} />
                <Row label="入口" value={detail.ui.entry} />
                <Row
                  label="最低客户端"
                  value={detail.ui.minClientVersion ?? '不限'}
                />
                <div className="pt-1">
                  <div className="text-xs text-ink-faint">支持的容器</div>
                  <div className="mt-1 flex flex-wrap gap-1.5">
                    {detail.ui.surfaces.map((s) => (
                      <span
                        key={s.type}
                        className="rounded border border-line px-2 py-0.5 text-xs text-ink-soft"
                        title={s.entry}
                      >
                        {s.type}
                      </span>
                    ))}
                    {detail.ui.surfaces.length === 0 && (
                      <span className="text-xs text-ink-faint">没有声明任何容器</span>
                    )}
                  </div>
                </div>
              </div>
            </section>

            {/* 动作 —— 界面之外的那一半: 数字人用的是同一份清单 */}
            <section className="mt-8">
              <h2 className="text-sm font-medium text-ink-soft">
                动作 <span className="text-ink-faint">({actions.length})</span>
              </h2>
              <div className="mt-3 space-y-2">
                {actions.map((spec) => (
                  <div key={spec.actionId} className="card">
                    <div className="text-ink">{spec.actionId}</div>
                    <div className="mt-1 text-xs text-ink-faint">
                      {spec.permissionLevel} · 风险 {spec.riskLevel}
                    </div>
                    {spec.description && (
                      <p className="mt-1 text-sm text-ink-soft">{spec.description}</p>
                    )}
                  </div>
                ))}
                {actions.length === 0 && (
                  <p className="text-sm text-ink-faint">这个应用没有已发布的动作。</p>
                )}
              </div>
            </section>
          </>
        )}
      </main>
    </div>
  )
}

function Row({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="flex items-baseline justify-between gap-4">
      <span className="text-ink-faint">
        {label}
        {hint && <span className="ml-2 text-xs text-ink-faint">{hint}</span>}
      </span>
      <span className="truncate text-ink-soft">{value}</span>
    </div>
  )
}
