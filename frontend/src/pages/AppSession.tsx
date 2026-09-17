import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { ArrowLeft, Check, Copy, Layers, LogOut, Trash2, UserPlus, Users } from 'lucide-react'
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
import Capsule from '@/components/mini/Capsule'
import { HostRuntimeProvider } from '@/application-host/useHostRuntime'
import ActionSheet, { SheetSection } from '@/components/mini/ActionSheet'
import { ListRow } from '@/components/im/ListRow'

/**
 * 一场应用会话 —— 也就是**小程序跑起来的地方**。
 *
 * <pre>
 *   /applications/{applicationId}/sessions/{sessionId}[?surface=TYPE]
 * </pre>
 *
 * <h2>这一页现在有两种形态, 而且默认那种是"没有平台"的那一种</h2>
 *
 * 之前这一页长这样: 一条聊天平台的头部(返回箭头 + 应用名 + 会话 id + 离开/结束),
 * 一条"以…打开 FULL_PAGE EMBEDDED MODAL PANEL INLINE"的工具栏, 然后才是应用,
 * 再往下是参与者名单与邀请区。
 *
 * 用户看完说: 「点进去怎么不是像人家微信直接打开小程序的前端界面呢? 为何还是在
 * 聊天平台通过聊天平台的栏目来进行操作?」—— 那条头部与那条工具栏, 就是"聊天平台
 * 的栏目"。它们把应用挤成了网页中间的一块, 而那一页底下写着的目标恰恰是
 * "应用本身不需要改变"(§67)。
 *
 * 所以改成:
 *
 * <pre>
 *   surface=FULL_PAGE  →  小程序运行时: 应用铺满整屏, 平台上只剩右上角一枚悬浮胶囊
 *   其余四种 surface     →  容器预览: 开发者用来看"同一份界面摆进别的框里什么样"
 * </pre>
 *
 * 判据是 `surface` 本身, 不是另立一个开关 —— 因为这两种形态要回答的问题本来就不同:
 * `FULL_PAGE` 问的是"用户要用这个应用", 其余四种问的是"这个应用能不能被摆进别处"。
 * 五条 entry 仍然指进同一个路径, 只是 `?surface=` 不同, 这一点没有变。
 *
 * <h2>参与者与邀请去哪了</h2>
 *
 * 收进胶囊的 `···`(见 {@link Capsule} 与 {@link ActionSheet})。它们没有被删除, 只是
 * 从"每个用户都必须先看过一遍"变成了"想知道的人点两下"。微信的小程序资料页也是这个
 * 形状 —— 会话 id、这一场里有谁、那张邀请链接, 都不是打开应用时要看的东西。
 *
 * <h2>URL 里的 `?surface=` 仍然是真的在工作</h2>
 * 它不是给页面看的装饰: manifest 里五条 surface 的 entry 分别指向同一个路径的不同
 * `?surface=`, 于是"聊天里内嵌打开"与"整页打开"进的是同一个页面、同一份界面实现,
 * 只是外面那层框不同。这就是 §67 想要的"应用本身不需要改变"。
 */

/** 五种容器, 顺序与 `SurfaceType` 的定义一致 —— 预览页的切换条与小票面板共用。 */
const SURFACES: SurfaceType[] = ['FULL_PAGE', 'EMBEDDED', 'MODAL', 'PANEL', 'INLINE']

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
  const [sheetOpen, setSheetOpen] = useState(false)

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
      //那不是错误, 只是"这一栏不对你显示", 所以静默吞掉。
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
    setSheetOpen(false)
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
      navigate('/discover')
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
      navigate('/discover')
    } catch (e) {
      report(e)
    } finally {
      setBusy(false)
    }
  }

  const me = session?.participant
  const iAmOwner = me?.role === 'OWNER'

  // ── 还没加载出来 ──
  // 这一屏在两种形态下都是同一段 —— 所以放在分支之前, 不重复两遍。
  if (!session || !detail) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-3 bg-surface px-6 text-center">
        {error ? (
          <>
            <p className="text-sm text-danger">{error}</p>
            <Link to="/discover" className="btn-outline text-xs">
              回发现
            </Link>
          </>
        ) : (
          <p className="text-sm text-ink-faint">正在加载会话…</p>
        )}
      </div>
    )
  }

  // ── 形态一: 小程序运行时 ───────────────────────────────────
  //
  // `overflow-y-auto` 在宿主上, 不在应用上: 应用可能比视口高(棋盘、长列表), 滚的是
  // 这里, 胶囊留在原地(`sticky`, 见下)。
  //
  // <h2>为什么桌面端是一条居中的窄列</h2>
  //
  // 手机上应用铺满整屏就对了 —— 屏幕本来就是窄的。桌面端照搬会得到相反的观感:
  // 一个 15×15 的棋盘点在大屏左上角, 右边一大片空白, 而胶囊飘在视口最右。
  //
  // 微信桌面版的做法是让小程序跑在一个手机宽度的窗口里, 这个平台的其它每一屏也
  // 已经这么做了(`max-w-[520px]` / `max-w-[600px]`, 见 Contacts 与 Discover)——
  // 所以小程序运行时跟着同一条规矩, 而不是自成一个例外。
  if (surface === 'FULL_PAGE') {
    return (
      /**
       * §7/§9/§10/§15 的宿主运行时 —— 聊天浮窗、分享单子、以及那条把应用接上来的桥。
       *
       * <h2>为什么 Provider 挂在这一个分支里, 而不是整个页面上</h2>
       *
       * `conversationId` 要等会话加载完才知道, 而它在**这一支**里才有意义: 容器预览那一支
       * 回答的是"这个应用能不能被摆进别的框里", 它没有"用户正在用这个应用"这回事 ——
       * 也就没有"边玩边聊"与"邀请一个朋友"。
       *
       * 传给它的 `conversationId` 是**应用看到的 `session.context.conversationId`**, 也
       * 是浮窗默认打开的那一段对话。为空(从分享链接直接进来)不是错误: 那时浮窗先给一份
       * 会话列表让用户挑。
       */
      <HostRuntimeProvider conversationId={session.conversationId ?? undefined}>
      <div className="mx-auto h-full w-full max-w-[520px] overflow-y-auto bg-surface sm:border-x sm:border-line">
        {/*
          胶囊的定位层。三个约束缺一不可:
          - **必须在应用之前**: `sticky` 的元素只在"它本来会被滚出去"时才钉住;
            放在后面, 它的自然位置在整块内容的下方, 于是它在滚动之前根本不在顶上。
          - **`h-0`**: 它不占纵向空间, 应用因此仍然从容器最顶上开始 —— 胶囊是浮在
            应用之上的, 这正是微信的样子。
          - **`pointer-events-none`**: 这一层横跨整行, 不关掉就会挡住底下应用右上角
            那一块, 应用里点不动。胶囊自己用 `pointer-events-auto` 收回来。
        */}
        <div className="pointer-events-none sticky top-3 z-40 flex h-0 justify-end pr-3">
          <div className="pointer-events-auto">
            <Capsule busy={busy} onMore={() => setSheetOpen(true)} onLeave={leave} />
          </div>
        </div>

        {/*
          刻意不传 `title` 也不传 `onClose` —— `Frame` 里那一行是
          `{title || onClose ? <Chrome/> : null}`, 两个都不给时它就不画头部。
          加上 `bleed` 去掉内边距, 应用这才真的拥有整个视口。
        */}
        <SurfaceHost
          applicationId={appId}
          sessionId={sessionId}
          ui={detail.ui}
          surface="FULL_PAGE"
          bleed
        />

        {/* 出错了要看得见, 但不能往应用里插一张卡片 —— 所以在下面浮一条。
            `max-w` + 两侧同时给 inset 再 `mx-auto`, 是让它跟着上面那条窄列居中,
            而不是横跨整个桌面视口。 */}
        {error && (
          <div className="fixed inset-x-3 bottom-3 z-40 mx-auto max-w-[496px] rounded-lg border border-danger/30 bg-raised px-3 py-2 text-xs leading-5 text-danger shadow-pop">
            {error}
          </div>
        )}

        {sheetOpen && (
          <ActionSheet
            title={detail.name || appId}
            subtitle={`这一场 ${sessionId.slice(0, 8)}… · ${session.status}`}
            onClose={() => setSheetOpen(false)}
          >
            <SheetSection label={`参与者 · ${session.participantCount}`}>
              {participants.map((p) => (
                <ListRow
                  key={p.participantId}
                  leading={
                    <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-sunken text-ink-faint">
                      <Users size={15} />
                    </span>
                  }
                  // 主键是技术标识 —— 用等宽, 否则那一串随机字符会随比例字体左右抖
                  title={<span className="font-mono text-[13px]">{p.principalId}</span>}
                  subtitle={`${p.principalType} · ${p.role} · ${p.status}`}
                />
              ))}
            </SheetSection>

            <SheetSection label="邀请">
              {iAmOwner ? (
                <div className="px-4 pb-2 pt-1">
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={invite}
                      disabled={busy}
                      className="btn-primary !px-3 !py-1 text-xs"
                    >
                      <UserPlus size={13} />
                      生成分享链接
                    </button>
                    <span className="text-[11px] text-ink-faint">
                      链接只表达"加入这一场", 平台上只存它的哈希。
                    </span>
                  </div>

                  {minted && (
                    <div className="mt-2 rounded-lg border border-accent/50 bg-surface p-2">
                      <div className="text-[11px] text-ink-faint">
                        这张票的明文<b>只出现这一次</b> —— 丢了只能重铸。
                      </div>
                      <div className="mt-1.5 flex items-center gap-1.5">
                        <code className="min-w-0 flex-1 truncate rounded bg-sunken px-2 py-1 text-[11px] text-ink-soft">
                          {window.location.origin}
                          {minted.joinUrl}
                        </code>
                        <button type="button" onClick={copy} className="btn-ghost !px-2 !py-1">
                          {copied ? <Check size={13} /> : <Copy size={13} />}
                          <span className="text-[11px]">{copied ? '已复制' : '复制'}</span>
                        </button>
                      </div>
                    </div>
                  )}

                  {invitations.map((inv) => (
                    <div
                      key={inv.invitationId}
                      className="mt-1.5 flex items-center justify-between gap-2 rounded-lg bg-sunken px-2 py-1 text-[11px] text-ink-soft"
                    >
                      <span className="min-w-0 truncate">
                        {inv.role} · 用了 {inv.usedCount}
                        {inv.maxUses ? `/${inv.maxUses}` : ''} · {inv.status}
                        {inv.targetId ? ` · 定向 ${inv.targetId}` : ''}
                      </span>
                      <button
                        type="button"
                        onClick={() => lap.revokeInvitation(inv.invitationId).then(load).catch(report)}
                        className="btn-ghost shrink-0 !px-2 !py-0.5 text-[11px]"
                      >
                        收回
                      </button>
                    </div>
                  ))}
                </div>
              ) : (
                <p className="px-4 pb-2 pt-1 text-xs text-ink-faint">
                  只有这一场的主人能发邀请。你可以让主人把链接发给你。
                </p>
              )}
            </SheetSection>

            <SheetSection label="这一场">
              <ListRow
                leading={
                  <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-sunken text-ink-faint">
                    <LogOut size={15} />
                  </span>
                }
                title="离开"
                subtitle="你走, 这一场还在, 别人还能继续"
                onClick={leave}
              />
              {iAmOwner && (
                <ListRow
                  leading={
                    <span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-sunken text-danger">
                      <Trash2 size={15} />
                    </span>
                  }
                  title="结束这一场"
                  subtitle="对所有人结束; 应用本身不受影响"
                  onClick={end}
                />
              )}
            </SheetSection>

            {/*
              容器预览的入口。放在最后、标成"开发者", 因为它是**平台的能力**, 不是
              用户要做的事: 五条 entry 指向同一个路径的不同 `?surface=`, 想验证
              manifest 的人从这里进去。
            */}
            <SheetSection label="容器预览 · 开发者">
              <div className="flex flex-wrap gap-1.5 px-4 pb-2 pt-1">
                {SURFACES.filter((t) => t !== 'FULL_PAGE').map((t) => (
                  <button
                    key={t}
                    type="button"
                    onClick={() => switchSurface(t)}
                    className="btn-ghost !px-2.5 !py-1 text-[11px]"
                  >
                    <Layers size={12} />
                    {t}
                  </button>
                ))}
              </div>
              <p className="px-4 pb-2 font-mono text-[10px] leading-4 text-ink-faint break-all">
                {linkFor('FULL_PAGE') || '—'}
              </p>
            </SheetSection>
          </ActionSheet>
        )}
      </div>
      </HostRuntimeProvider>
    )
  }

  // ── 形态二: 容器预览 ───────────────────────────────────────
  //
  // 这一屏**是**给开发者看的, 所以它保留"以…打开"那条切换栏与那条头部 —— 那些栏目
  // 在这里不是噪音, 正是被观察的对象。它不再是任何用户的必经之路: 从「发现」打开
  // 应用进的是上面那一种。
  return (
    <div className="h-full overflow-y-auto bg-surface">
      <header className="sticky top-0 z-10 border-b border-line bg-surface/80 backdrop-blur-md">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-3 px-5 py-4">
          <button
            type="button"
            onClick={() => switchSurface('FULL_PAGE')}
            className="btn-ghost !px-3 !py-1.5"
            title="回到小程序"
          >
            <ArrowLeft size={15} />
          </button>
          <span className="text-lg text-ink">{detail.name || appId}</span>
          <span className="text-xs text-ink-faint">
            容器预览 · 会话 {sessionId.slice(0, 8)}…
          </span>
          <Link
            to={`/applications/${encodeURIComponent(appId)}`}
            className="ml-auto text-xs text-ink-faint hover:text-ink"
          >
            应用详情
          </Link>
        </div>
      </header>

      <main className="mx-auto max-w-5xl px-5 py-6">
        {error && (
          <div className="card mb-6 border-danger/30 bg-danger/10 text-sm text-danger">{error}</div>
        )}

        <div className="mb-4 flex flex-wrap items-center gap-2">
          <span className="text-xs text-ink-faint">以…打开</span>
          {SURFACES.map((type) => (
            <button
              key={type}
              type="button"
              onClick={() => switchSurface(type)}
              className={type === surface ? 'btn-primary !px-3 !py-1 text-xs' : 'btn-ghost !px-3 !py-1 text-xs'}
            >
              {type}
            </button>
          ))}
          <span className="ml-auto font-mono text-[11px] text-ink-faint">{linkFor(surface) || '—'}</span>
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

        <p className="mt-6 text-xs leading-relaxed text-ink-faint">
          这一屏是容器预览 —— 同一份应用界面摆在五种外框里的样子。用户看到的不是它:
          从「发现」打开应用会直接进小程序。
        </p>
      </main>
    </div>
  )
}
