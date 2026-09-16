import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { ArrowLeft, Check, Copy, LogOut, Trash2, UserPlus, Users } from 'lucide-react'
import {
  lap,
  LapError,
  type ApplicationDetail,
  type InvitationView,
  type MintedInvitation,
  type ParticipantView,
  type SessionResponse,
  type SurfaceType,
} from '@/api/lap'
import SurfaceHost from '@/surfaces/SurfaceHost'

/**
 * Session 页 —— 一场应用会话的现场。
 *
 * <pre>
 *   /applications/{applicationId}/sessions/{sessionId}[?surface=TYPE]
 * </pre>
 *
 * 这条路径是 §16 图里最后那一步, 也是 LAP v2 里"一个会话"最完整的形态:
 * 界面上半是<b>应用本身</b>(经 SurfaceHost 摆进五种容器之一), 下半是<b>这一场里的人</b>与
 * <b>把别人请进来的那张票</b>。
 *
 * <h2>为什么参与者名单与邀请链接和棋盘同页</h2>
 * 因为它们回答的是同一个问题: "这一场现在是什么样"。把邀请藏进二级页, 表现就是
 * 一局棋永远只有一个人 —— 而 v2 的全部要点正是"多个 principal 共用同一个应用实例"。
 *
 * <h2>URL 里的 `?surface=` 是真的在工作</h2>
 * 它不是给页面看的装饰: manifest 里五条 surface 的 entry 分别指向同一个路径的不同
 * `?surface=`, 于是"聊天里内嵌打开"与"整页打开"进的是同一个页面、同一份界面实现,
 * 只是外面那层框不同。这就是 §67 想要的"应用本身不需要改变"。
 */
export default function AppSession() {
  const { applicationId = '', sessionId = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()

  const surface = (params.get('surface') ?? 'FULL_PAGE') as SurfaceType

  const [session, setSession] = useState<SessionResponse | null>(null)
  const [detail, setDetail] = useState<ApplicationDetail | null>(null)
  const [participants, setParticipants] = useState<ParticipantView[]>([])
  const [invitations, setInvitations] = useState<InvitationView[]>([])
  const [minted, setMinted] = useState<MintedInvitation | null>(null)

  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)

  const report = useCallback((e: unknown) => {
    if (e instanceof LapError) setError(`${e.code} — ${e.message}`)
    else setError(e instanceof Error ? e.message : String(e))
  }, [])

  const load = useCallback(async () => {
    try {
      // 这个页面有两条入口: 从应用详情进来(路径里带 applicationId), 或者从分享链接
      // 兑票后直接进来(只知道 sessionId)。后者要靠会话自己说出"我是什么应用" ——
      // §16 的响应里 `application.id` 就是为这一条路准备的。
      const s = await lap.session(sessionId)
      const appId = applicationId || s.application.id
      setSession(s)

      const [d, p] = await Promise.all([lap.application(appId), lap.participantsOf(sessionId)])
      setDetail(d)
      setParticipants(p)
      // 邀请列表只有主人看得到 —— 别人点进来会拿到 NOT_SESSION_OWNER。
      // 那不是错误, 只是"这一栏不对你显示", 所以静默吞掉。
      setInvitations(await lap.invitationsOf(sessionId).catch(() => []))
    } catch (e) {
      report(e)
    }
  }, [applicationId, sessionId, report])

  useEffect(() => {
    void load()
  }, [load])

  /** 会话自己知道它是什么应用 —— 从分享链接进来时路径里没有这一段。 */
  const appId = applicationId || session?.application.id || ''

  /** 把入口模板里的 `{sessionId}` 换成真的 id —— 就是 §68 说的"客户端只做替换"。 */
  const linkFor = (type: SurfaceType) => {
    const declared = detail?.ui.surfaces.find((s) => s.type === type)
    const template = declared?.entry ?? detail?.ui.entry ?? ''
    return template
      .replaceAll('{applicationId}', encodeURIComponent(appId))
      .replaceAll('{sessionId}', sessionId)
  }

  const switchSurface = (type: SurfaceType) => {
    setParams((prev) => {
      const next = new URLSearchParams(prev)
      next.set('surface', type)
      return next
    })
  }

  const invite = async () => {
    setBusy(true)
    setError(null)
    try {
      const ticket = await lap.invite(sessionId, { role: 'PARTICIPANT' })
      setMinted(ticket)
      setInvitations(await lap.invitationsOf(sessionId).catch(() => []))
    } catch (e) {
      report(e)
    } finally {
      setBusy(false)
    }
  }

  const copy = async () => {
    if (!minted) return
    const url = `${window.location.origin}${minted.joinUrl}`
    try {
      await navigator.clipboard.writeText(url)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch {
      // 剪贴板被拒(非 https / 权限)时别假装成功 —— 把链接摆出来让人自己选。
      setError(`复制失败, 请手动复制: ${url}`)
    }
  }

  const leave = async () => {
    setBusy(true)
    try {
      await lap.leave(sessionId)
      navigate('/applications')
    } catch (e) {
      report(e)
    } finally {
      setBusy(false)
    }
  }

  const end = async () => {
    setBusy(true)
    try {
      await lap.endSession(sessionId)
      navigate('/applications')
    } catch (e) {
      report(e)
    } finally {
      setBusy(false)
    }
  }

  const me = session?.participant
  const iAmOwner = me?.role === 'OWNER'

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
        <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-3 px-5 py-4">
          <Link
            to={`/applications/${encodeURIComponent(appId)}`}
            className="btn-ghost !px-3 !py-1.5"
            title="回到应用详情"
          >
            <ArrowLeft size={15} />
          </Link>
          <span className="text-lg text-ink">
            {detail?.name || appId}
          </span>
          <span className="text-xs text-ink-faint">
            会话 {sessionId.slice(0, 8)}… · {session?.status ?? '…'}
          </span>
          <div className="ml-auto flex items-center gap-2">
            <button type="button" onClick={leave} disabled={busy} className="btn-ghost !px-3 !py-1 text-xs">
              <LogOut size={13} />
              离开
            </button>
            {iAmOwner && (
              <button
                type="button"
                onClick={end}
                disabled={busy}
                className="btn-ghost !px-3 !py-1 text-xs"
                title="结束这一场 (应用本身不受影响)"
              >
                <Trash2 size={13} />
                结束
              </button>
            )}
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-5xl px-5 py-8">
        {error && (
          <div className="card mb-6 border-danger/30 bg-danger/10 text-sm text-danger">
            {error}
          </div>
        )}

        {!session && !error && <p className="text-sm text-ink-faint">正在加载会话…</p>}

        {session && detail && (
          <>
            {/* 容器切换 —— 五种 Surface 在这一个页面上都能试 */}
            <div className="mb-4 flex flex-wrap items-center gap-2">
              <span className="text-xs text-ink-faint">以…打开</span>
              {(['FULL_PAGE', 'EMBEDDED', 'MODAL', 'PANEL', 'INLINE'] as SurfaceType[]).map((type) => (
                <button
                  key={type}
                  type="button"
                  onClick={() => switchSurface(type)}
                  className={type === surface ? 'btn-primary !px-3 !py-1 text-xs' : 'btn-ghost !px-3 !py-1 text-xs'}
                >
                  {type}
                </button>
              ))}
              <span className="ml-auto font-mono text-[11px] text-ink-faint">
                {linkFor(surface) || '—'}
              </span>
            </div>

            <SurfaceHost
              applicationId={appId}
              sessionId={sessionId}
              ui={detail.ui}
              surface={surface}
              title={detail.name ?? appId}
              onClose={() => switchSurface('FULL_PAGE')}
              onExpand={() => switchSurface('FULL_PAGE')}
            />

            {/* 这一场里有谁 */}
            <section className="mt-10">
              <h2 className="flex items-center gap-2 text-sm font-medium text-ink-soft">
                <Users size={15} />
                参与者 <span className="text-ink-faint">({session.participantCount})</span>
              </h2>
              <div className="mt-3 space-y-2">
                {participants.map((p) => (
                  <div
                    key={p.participantId}
                    className="card flex flex-wrap items-center justify-between gap-2 text-sm"
                  >
                    <div>
                      <span className="text-ink">{p.principalId}</span>
                      <span className="ml-2 text-xs text-ink-faint">
                        {p.principalType} · {p.role}
                      </span>
                    </div>
                    <span
                      className={
                        p.status === 'ACTIVE' ? 'text-xs text-ok' : 'text-xs text-ink-faint'
                      }
                    >
                      {p.status}
                      {p.capabilities?.length ? ` · ${p.capabilities.join(', ')}` : ''}
                    </span>
                  </div>
                ))}
              </div>
            </section>

            {/* 把人请进来 */}
            <section className="mt-10">
              <h2 className="flex items-center gap-2 text-sm font-medium text-ink-soft">
                <UserPlus size={15} />
                邀请
              </h2>

              {iAmOwner ? (
                <>
                  <div className="mt-3 flex items-center gap-2">
                    <button type="button" onClick={invite} disabled={busy} className="btn-primary">
                      生成分享链接
                    </button>
                    <span className="text-xs text-ink-faint">
                      链接只表达"加入这一场"; 平台上只存它的哈希。
                    </span>
                  </div>

                  {minted && (
                    <div className="card mt-3 border-accent/50">
                      <div className="text-xs text-ink-faint">
                        这张票的明文<b>只出现这一次</b> —— 丢了只能重铸。
                      </div>
                      <div className="mt-2 flex items-center gap-2">
                        <code className="flex-1 truncate rounded bg-raised px-2 py-1 text-xs text-ink-soft">
                          {window.location.origin}
                          {minted.joinUrl}
                        </code>
                        <button type="button" onClick={copy} className="btn-ghost !px-2 !py-1">
                          {copied ? <Check size={14} /> : <Copy size={14} />}
                          {copied ? '已复制' : '复制'}
                        </button>
                      </div>
                    </div>
                  )}

                  {invitations.length > 0 && (
                    <div className="mt-3 space-y-2">
                      {invitations.map((inv) => (
                        <div
                          key={inv.invitationId}
                          className="card flex flex-wrap items-center justify-between gap-2 text-xs"
                        >
                          <span className="text-ink-soft">
                            {inv.role} · 用了 {inv.usedCount}
                            {inv.maxUses ? `/${inv.maxUses}` : ''} · {inv.status}
                            {inv.targetId ? ` · 定向 ${inv.targetId}` : ''}
                          </span>
                          <button
                            type="button"
                            onClick={() => lap.revokeInvitation(inv.invitationId).then(load).catch(report)}
                            className="btn-ghost !px-2 !py-0.5"
                          >
                            收回
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                </>
              ) : (
                <p className="mt-3 text-sm text-ink-faint">
                  只有这一场的主人能发邀请。你可以让主人把链接发给你。
                </p>
              )}
            </section>
          </>
        )}
      </main>
    </div>
  )
}
