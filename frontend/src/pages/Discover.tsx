import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Boxes, Play } from 'lucide-react'
import { EmptyState } from '@/components/im/EmptyState'
import { PanelError, Skeleton } from '@/components/agent/PanelState'
import { Hint } from '@/components/ui/Hint'
import {
  lap,
  describeLapError,
  type ApplicationView,
  type CapabilityView,
  type SessionResponse,
} from '@/api/lap'

/**
 * 「发现」—— 微信四 tab 里的第三格。
 *
 * <h2>为什么这一屏直接就是应用市场, 而不是一张"小程序 ›"的入口列表</h2>
 *
 * 微信的「发现」是一列入口(朋友圈 / 视频号 / 扫一扫 / 小程序), 因为那一屏有六七个
 * 各不相同的东西。而这个平台今天在「发现」下面只有**一样**东西: 应用。
 *
 * 给一样东西做一个只有一行的目录, 换来的是每次进来多一次点击, 以及一个必须永远
 * 与真实数量保持同步的中间页 —— 而它一旦不同步, 症状是"入口在但里面是空的"。
 * 所以这里把市场本身当成了这一屏。等二期真有第二样东西(比如"附近的 Agent")时,
 * 那时候再引入一层目录才是对的; 那时改动也只是在这一屏上面加一段列表。
 *
 * <h2>两种逛法, 因为来的人有两种</h2>
 *
 * <ul>
 *   <li><b>知道要做什么</b>("我想下棋") —— 按能力筛。能力就是"我想干什么"的词表。</li>
 *   <li><b>只是想看看</b> —— 直接列全部在架应用。</li>
 * </ul>
 *
 * 能力筛选是<b>客户端</b>筛的(拿应用列表与能力列表在本地对), 不是多一次请求: 市场本来
 * 就一次把在架应用拿全了, 再为每次点能力跑一趟服务端只会让切换变慢。`GET
 * /api/v1/capabilities/{id}/applications` 是存在的, 但它是给"我知道要什么、且不想
 * 把整个市场拉下来"的客户端用的 —— 这里不是那种情况。
 *
 * <h2>点卡片 = 开应用, 而不是"先看一页介绍"</h2>
 *
 * 这里改过一次。原来卡片正文进详情页、「打开」按钮才开局, 理由是"我想了解"与"我要
 * 开一局"是两种意图。用户看完说: 「点进去怎么不是像人家微信直接打开小程序的前端
 * 界面呢?」—— 在微信里, 点一个小程序图标**就是**打开它; 没有"详情页"这一站。
 *
 * 所以现在整个卡片是一个动作: 建会话、进会话页(也就是小程序运行时)。「详情」降级成
 * 一个次要的小按钮, 给那少数真的想先看清单里声明了什么的人 —— 它仍然在, 只是不再
 * 挡在默认路径上。
 *
 * 一个入口挡住默认路径的代价, 与"用户已经知道自己要什么却还要再看一页"是同一件事,
 * 而它发生在**每一个**用户身上。这就是为什么两种意图里该让默认路径服务于多数。
 *
 * <h2>这一页里没有一个应用的名字</h2>
 *
 * 名字、图标、描述全部来自 `lap.market()` 的响应。写死一张表会让"新应用接进来"
 * 从一个零改动的动作变成一次前端发版 —— `ApplicationCardBubble.test.tsx` 有一条
 * 源码扫描在守这件事, 这个文件在那张扫描表里。
 */
export default function Discover() {
  const navigate = useNavigate()
  const [applications, setApplications] = useState<ApplicationView[]>([])
  const [capabilities, setCapabilities] = useState<CapabilityView[]>([])
  const [capability, setCapability] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const report = useCallback((e: unknown) => setError(describeLapError(e)), [])

  useEffect(() => {
    let cancelled = false
    Promise.all([lap.market(), lap.capabilities()])
      .then(([apps, caps]) => {
        if (cancelled) return
        setApplications(apps)
        setCapabilities(caps)
        setLoading(false)
      })
      .catch((e) => {
        if (cancelled) return
        report(e)
        setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [report])

  /**
   * 直接开一局 —— 给"我已经知道要下棋, 别让我再看一页"的人。
   *
   * 它走的端点与详情页里的"打开"完全一样; 这里只是少了一次跳转, 不是另一条捷径。
   */
  async function openNow(applicationId: string) {
    setBusy(true)
    setError('')
    try {
      const session: SessionResponse = await lap.openSession(applicationId)
      navigate(`/applications/${encodeURIComponent(applicationId)}/sessions/${session.sessionId}`)
    } catch (e) {
      report(e)
    } finally {
      setBusy(false)
    }
  }

  const shown = capability
    ? applications.filter((a) => a.capabilities?.includes(capability))
    : applications

  return (
    <div className="mx-auto w-full max-w-[600px]">
      <header className="sticky top-0 z-10 border-b border-line bg-surface/85 backdrop-blur">
        <div className="flex items-center gap-2 px-4 py-3">
          <h1 className="text-lg font-semibold tracking-tight text-ink">发现</h1>
          {/*
            这里原来是一整段常驻的说明("点开一个应用就是开一场会话…"), 页脚还有
            第二段("数字人走的是同一个市场…")。两段都是**读一次就够**的话, 却占着
            每一次进入这一屏的第一眼 —— 用户的原话是这几屏「制作的太烂了」。
            现在它们合成一个 `?`: 想知道的人点开, 不想知道的人直接看到应用。
          */}
          <Hint label="这一屏是什么">
            点开一个应用就是开一场会话 —— 应用会占满整屏, 可以邀请真人和数字人一起进来。
            数字人走的是同一个市场、同一条打开链路, 它们没有专用接口。
          </Hint>
        </div>
      </header>

      <div className="px-4 py-4">
        {error && (
          <div className="mt-4">
            <PanelError message={error} />
          </div>
        )}

        {loading && (
          /*
            骨架按**下面真实布局的形状**摆: 一行筛选胶囊 + 两列卡片。
            原来是一句"正在看有什么可以玩…"配一个转圈 —— 那段文字与屏幕上将要出现
            的东西没有任何对应关系, 数据到了还是整块换掉。
          */
          <div aria-busy aria-label="正在加载应用">
            <div className="mt-4 flex flex-wrap gap-2">
              <Skeleton className="h-6 w-12 rounded-full" />
              <Skeleton className="h-6 w-20 rounded-full" />
              <Skeleton className="h-6 w-16 rounded-full" />
            </div>
            <div className="mt-4 grid gap-3 sm:grid-cols-2">
              {[0, 1, 2, 3].map((i) => (
                <div key={i} className="card flex flex-col gap-3 p-3">
                  <Skeleton className="h-4 w-24" />
                  <Skeleton className="h-3 w-32" />
                  <Skeleton className="h-3 w-full" />
                  <div className="mt-auto flex gap-2 pt-1">
                    <Skeleton className="h-6 w-16 rounded-lg" />
                    <Skeleton className="h-6 w-12 rounded-lg" />
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {!loading && (
          <>
            {/* 能力筛选。`全部` 是显式的第一项而不是"再点一次取消" ——
                后者要求用户猜到当前选中的那个按钮是可点的开关 */}
            {capabilities.length > 0 && (
              <div className="mt-4 flex flex-wrap gap-2">
                <button
                  type="button"
                  onClick={() => setCapability(null)}
                  aria-pressed={capability === null}
                  className={capability === null ? 'btn-primary !px-3 !py-1 text-xs' : 'btn-ghost !px-3 !py-1 text-xs'}
                >
                  全部
                </button>
                {capabilities.map((c) => (
                  <button
                    key={c.capabilityId}
                    type="button"
                    onClick={() => setCapability(c.capabilityId)}
                    aria-pressed={c.capabilityId === capability}
                    className={
                      c.capabilityId === capability
                        ? 'btn-primary !px-3 !py-1 text-xs'
                        : 'btn-ghost !px-3 !py-1 text-xs'
                    }
                    title={c.description ?? undefined}
                  >
                    {c.title || c.capabilityId}
                  </button>
                ))}
              </div>
            )}

            {shown.length === 0 ? (
              <EmptyState
                icon={<Boxes size={28} />}
                title={capability ? '这个能力下暂时没有在架的应用' : '市场里还没有应用'}
                hint={
                  capability
                    ? '换一个能力看看, 或者点「全部」。'
                    : '应用接进来之后会自动出现在这里 —— 这里没有一张写死的清单。'
                }
                action={
                  capability ? (
                    <button
                      type="button"
                      className="btn-outline text-xs"
                      onClick={() => setCapability(null)}
                    >
                      看全部
                    </button>
                  ) : undefined
                }
              />
            ) : (
              <div className="mt-4 grid gap-3 sm:grid-cols-2">
                {shown.map((a) => (
                  <div key={a.applicationId} className="card flex flex-col gap-3 p-3">
                    {/* 整张卡片就是一个动作 —— 点开就是打开它, 与点微信里的小程序图标
                        是同一种预期。见文件头那段。 */}
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => void openNow(a.applicationId)}
                      title={`打开 ${a.name || a.applicationId}`}
                      className="-mx-1 block rounded-lg px-1 py-0.5 text-left transition-colors hover:bg-sunken/70 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/40 disabled:opacity-60"
                    >
                      <span className="block text-[15px] font-medium text-ink">
                        {a.name || a.applicationId}
                      </span>
                      {/* 版本号用等宽 —— 这类技术标识在比例字体里会随数字抖动。
                          `version` 缺省时整段不画: 原来它无条件拼一个 " v", 于是没有
                          版本号的应用下面挂着一个孤零零的字母。 */}
                      <span className="mt-0.5 block font-mono text-[11px] text-ink-faint">
                        {a.applicationId}
                        {a.version ? ` v${a.version}` : ''}
                      </span>
                      {a.description && (
                        <span className="mt-2 block text-[13px] leading-5 text-ink-soft">
                          {a.description}
                        </span>
                      )}
                    </button>
                    <div className="mt-auto flex items-center gap-2">
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => void openNow(a.applicationId)}
                        className="btn-primary !px-3 !py-1 text-xs disabled:opacity-55"
                      >
                        <Play size={13} />
                        打开
                      </button>
                      <button
                        type="button"
                        onClick={() => navigate(`/applications/${encodeURIComponent(a.applicationId)}`)}
                        title={`看看 ${a.name || a.applicationId} 的清单`}
                        className="btn-ghost !px-3 !py-1 text-xs"
                      >
                        详情
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
