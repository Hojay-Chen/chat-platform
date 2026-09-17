import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { AtSign, ChevronLeft } from 'lucide-react'
import * as personApi from '@/api/person'
import { PanelError, PanelLoading, PanelSection } from '@/components/agent/PanelState'
import { useAgentData } from '@/hooks/useAgentData'
import { describeFailure, quotaLabel, type HandleFailure } from '@/lib/handles'

/**
 * 「我」→ 账号ID —— 真人自己改自己的号。
 *
 * <h2>为什么这一页必须存在, 而不只是"把入口从 Agent 设置页挪走"</h2>
 *
 * 改号这件事原先挂在 `AgentSettings.tsx` 上, 于是它读起来像"改那个 Agent 的设置"。而
 * 本次划定的规则是: **Agent 的账号ID 由系统分配、永久不变; 真人自己的账号ID 可以改**。
 * 两种主体、两种规则, 却共用一个界面 —— 那不是少做一个页面, 是让用户以为他也能改她的。
 *
 * 所以这个页面的主语是**你**: 标题是「我的账号ID」, 路径是 `/me/handle`, 后端端点是
 * `/api/persons/me/handle`(me 从已认证身份里取, 没有第二个参数可以填错)。
 *
 * <h2>取数用 `useAgentData` 而非裸 `useEffect`</h2>
 *
 * 这一页的 key 是常量 `'me'` —— 它会一直指向当前登录者, 同一个会话里不会变, 于是这个
 * hook 的"key 一变就清空"在这里是空转。用它是因为它同时给了 `loading` / `error` /
 * `reload`, 而这些是每一页都要写对的三件小事(见 `hooks/useAgentData.ts` 的文件头)。
 *
 * <h2>改成功之后不重新拉取</h2>
 *
 * `PUT` 的响应就是改完之后的现状(是完整的 `HandleView`, 含新的配额), 直接拿它覆盖显示。
 * 再打一次 `GET` 的话, 那一小段时间里界面上是**旧号 + 新配额**——一个从未存在过的组合。
 */
export default function Handle() {
  const navigate = useNavigate()
  // 显式写出 `| null`: 初值是 null, 而 `load` 返回 `HandleView` —— 让编译器把两边合起来
  // 而不是去猜哪一个说了算
  const { data, loading, error } = useAgentData<personApi.HandleView | null>('me', load, null)

  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<HandleFailure | null>(null)
  const [ok, setOk] = useState('')
  // 改成功之后这一份盖住服务端读到的那一份 —— 它就是最新的现状(见文件头)
  const [latest, setLatest] = useState<personApi.HandleView | null>(null)

  const hv = latest ?? data

  async function save() {
    const next = draft.trim()
    if (!next || busy) return
    setBusy(true)
    setFailure(null)
    setOk('')
    try {
      const updated = await personApi.updateMyHandle(next)
      setLatest(updated)
      setDraft('')
      // 「没有变化」也是一次成功的请求(同值不算改, 也不扣配额), 那就别报喜说改了 ——
      // 一句话说不存在的事, 用户下次就会拿它去核对一个不存在的差异
      setOk(updated.handle === hv?.handle ? '账号ID 没有变化。' : `账号ID 已改为 ${updated.handle}。`)
    } catch (e) {
      // 400 形状 / 409 被占 / 429 配额 —— 三种话都由后端的 `hint` 带着, 这里只取出来
      // 显示, 不解释也不改写。理由见 `lib/handles.ts` 的 `describeFailure`
      setFailure(describeFailure(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <header className="z-10 flex items-center gap-1 border-b border-line bg-surface/90 px-1.5 py-1.5 backdrop-blur">
        <button
          type="button"
          onClick={() => navigate('/me')}
          title="返回"
          className="grid h-9 w-9 shrink-0 place-items-center rounded-lg text-ink-soft transition-colors hover:bg-sunken hover:text-ink"
        >
          <ChevronLeft size={20} />
        </button>
        <span className="min-w-0 flex-1 truncate text-sm font-medium text-ink">账号ID</span>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto w-full max-w-2xl space-y-6 px-5 py-6">
          {loading && !data && <PanelLoading label="正在读你的账号ID…" />}

          {!loading && !hv && <PanelError message={error || '读不到你的账号ID'} />}

          {hv && (
            <>
              <section className="card p-5">
                <PanelSection
                  title="我的账号ID"
                  hint="名字可以重复, 账号ID 唯一 —— 别人靠它找到你, 而不是靠名字。"
                >
                  <div className="flex items-center gap-3 rounded-xl border border-line bg-sunken px-3 py-3">
                    {/* 图标只是把这个值和"一段可复制的文本"区分开 —— 它是一个地址,
                        别人会念它、抄它, 所以整行 select-all */}
                    <AtSign size={18} className="shrink-0 text-ink-faint" />
                    <span className="select-all break-all font-mono text-base text-ink">
                      {hv.handle ?? '（还没有分配, 用一次就会自动有）'}
                    </span>
                  </div>
                  <p className="text-xs text-ink-faint tnum">{quotaLabel(hv)}</p>
                </PanelSection>
              </section>

              {/*
                额度用尽时**不画输入框**。留着输入框 + 一个点不动的按钮, 等于让用户把
                刚想好的号打进去再被告知改不了 —— 那面墙该在他动手之前就立在那里。
              */}
              {hv.remaining > 0 ? (
                <section className="card p-5">
                  <PanelSection title="改账号ID" hint="改完之后, 旧号会记在案, 别人拿着它问起时说得清。">
                    <input
                      className="input font-mono"
                      placeholder="新的账号ID"
                      value={draft}
                      // Enter 直接提交 —— 一个只有一项的表单, 让用户去够鼠标是多余的
                      onChange={(e) => {
                        setDraft(e.target.value)
                        setFailure(null)
                        setOk('')
                      }}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') void save()
                      }}
                      disabled={busy}
                      autoCapitalize="none"
                      autoCorrect="off"
                      spellCheck={false}
                    />

                    {failure && (
                      <div className="rounded-xl border border-danger/30 bg-danger/10 px-3 py-2.5">
                        <p className="text-sm text-danger">{failure.message}</p>
                        {failure.hint && <p className="mt-1 text-xs text-ink-soft">{failure.hint}</p>}
                      </div>
                    )}
                    {ok && (
                      <p className="rounded-xl border border-ok/30 bg-ok/10 px-3 py-2 text-sm text-ok">
                        {ok}
                      </p>
                    )}

                    <button
                      type="button"
                      className="btn-primary"
                      onClick={() => void save()}
                      disabled={busy || !draft.trim()}
                    >
                      {busy ? '正在改…' : '保存'}
                    </button>

                    {/*
                      规则写在界面上, 而不是让用户试出来。这一段**不是校验** —— 前端不做
                      预判(见 `api/person.ts`: 一份前端副本就是一份会漂移的规则, 而漂移的
                      表现是"前端说可以, 后端说不行")。它只是把服务端那条规则的形状说出来。
                    */}
                    <p className="text-xs text-ink-faint">
                      6–24 位，以小写字母开头，只能用小写字母、数字、下划线（_）和短横线（-）。
                      <br />
                      以 <span className="font-mono text-ink-soft">agent_</span> 开头的账号ID
                      是系统分配给 Agent 的，你不能使用 —— 否则你就能冒充一个 Agent。
                    </p>
                  </PanelSection>
                </section>
              ) : (
                <section className="card p-5">
                  <PanelSection title="改账号ID">
                    <p className="text-sm text-ink-soft">{quotaLabel(hv)}</p>
                    <p className="text-xs text-ink-faint">
                      额度是滚动的：最早的那次修改满一年之后滑出窗口, 这里就会松开一次。
                    </p>
                  </PanelSection>
                </section>
              )}

              {/*
                这一段是为了让"我为什么能改、她为什么不能改"有一个答案。用户在这里看到的
                是**半条规则**, 而半条规则看起来就是随意的 —— 补上另一半, 它才是一条规则。
              */}
              <section className="card p-5">
                <PanelSection title="Agent 的账号ID">
                  <p className="text-sm text-ink-soft">
                    Agent 的账号ID 由系统分配、<span className="text-ink">永久不变</span> ——
                    它是那个 Agent 的标识, 不是可以改的名字。它们都以{' '}
                    <span className="font-mono">agent_</span> 开头, 一眼就能和人的区分开。
                  </p>
                </PanelSection>
              </section>
            </>
          )}
        </div>
      </div>
    </div>
  )
}

/**
 * `useAgentData` 按 key 取数, 而这个页面只有一个 key。
 *
 * 写成 `() => personApi.getMyHandle()` 而不是忽略参数, 是为了让"这个 key 是什么"在调用点
 * 显式可见 —— 该 hook 在 key 变化时会清空数据, 而这里它**不会**变: `me` 就是当前登录者。
 */
function load(_key: string): Promise<personApi.HandleView> {
  return personApi.getMyHandle()
}
