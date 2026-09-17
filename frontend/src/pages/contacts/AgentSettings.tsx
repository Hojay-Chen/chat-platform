import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ChevronLeft, Download, Sparkles, Trash2 } from 'lucide-react'
import * as agentApi from '@/api/agent'
import { PanelError, PanelLoading, PanelSection } from '@/components/agent/PanelState'
import { Avatar } from '@/components/im/Avatar'
import { useAgentData } from '@/hooks/useAgentData'
import { changeSourceZh } from '@/lib/agentLabels'
import { describeFailure, quotaLabel, type HandleFailure } from '@/lib/handles'
import { useCompanionStore } from '@/stores/companion'
import { format } from 'date-fns'

/**
 * Agent 设置页 —— 老 `Settings.tsx` 的归宿。
 *
 * <h2>它和资料页的分工</h2>
 *
 * 资料页回答「它是谁」, 设置页回答「我要改变它什么」。前者是读, 后者是写, 而且写的
 * 那几个动作里有两个**不可逆**(清空记忆、删除)。所以分成两页, 而不是在资料页底部加
 * 一栏按钮: 一个能一键删掉它的按钮, 不该和"看看它的记忆"挨在一起。
 *
 * <h2>不可逆动作的三个层次</h2>
 *
 * - 「更新人格」—— 不确认。它保留旧版本(人格版本历史就在下面), 本来就是可逆的
 * - 「清空记忆 / 清空它对你的了解」—— confirm 一次。不可逆, 但对象是数据不是人
 * - 「删除它」—— confirm 一次 + 单独一栏放在最底下 + 红色。这一栏在视觉上必须与
 *   上面所有东西分开, 因为它是这一屏唯一一个"点完之后它就不存在了"的按钮
 */
interface SettingsBundle {
  agent: Awaited<ReturnType<typeof agentApi.getAgent>>
  handle: agentApi.HandleView
  lifeEvents: Awaited<ReturnType<typeof agentApi.listLifeEvents>>
  reflections: Awaited<ReturnType<typeof agentApi.listReflections>>
  personaVersions: Awaited<ReturnType<typeof agentApi.listPersonaVersions>>
}

async function load(companionId: string): Promise<SettingsBundle> {
  const [agent, handle, lifeEvents, reflections, personaVersions] = await Promise.all([
    agentApi.getAgent(companionId),
    agentApi.getHandle(companionId),
    agentApi.listLifeEvents(companionId),
    agentApi.listReflections(companionId),
    agentApi.listPersonaVersions(companionId),
  ])
  return { agent, handle, lifeEvents, reflections, personaVersions }
}

export default function AgentSettings() {
  const { companionId } = useParams<{ companionId: string }>()
  const navigate = useNavigate()
  const removeCompanion = useCompanionStore((s) => s.remove)
  const patchCompanion = useCompanionStore((s) => s.patch)

  const { data, loading, error, reload } = useAgentData(companionId, load, null)
  const [description, setDescription] = useState('')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState('')
  const [actError, setActError] = useState('')

  /**
   * 账号ID 这一块的状态。
   *
   * <p>`handleView` 是**本地覆盖**: 改号成功后后端回的是改动后的配额, 直接用它, 而不是
   * 重拉整页(那会把下面三栏的人格版本、复盘、时间线一起抖一遍)。它是 null 时回落到
   * `load()` 拿到的那个 —— 于是"还没改过"与"改过了"走的是同一条渲染路径。
   */
  const [handleView, setHandleView] = useState<agentApi.HandleView | null>(null)
  const [handleInput, setHandleInput] = useState('')
  const [handleFailure, setHandleFailure] = useState<HandleFailure | null>(null)
  const [handleOk, setHandleOk] = useState('')
  const [handleBusy, setHandleBusy] = useState(false)

  if (!companionId) {
    return <PanelError message="没有指定联系人" />
  }
  // 收窄一次, 下面到处用。写成 `companionId!` 的话每多一处调用就多一次"我保证它非空"
  // 的口头声明, 而那个保证只在**这一行**被编译器检查过
  const id = companionId

  if (loading && !data) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <PanelLoading label="加载中…" />
      </div>
    )
  }

  if (!data) {
    return (
      <div className="flex flex-1 flex-col">
        <SettingsHeader name="设置" onBack={() => navigate('/contacts')} />
        <div className="px-5 py-5">
          <PanelError message={error || '找不到这个人'} />
        </div>
      </div>
    )
  }

  const { agent, lifeEvents, reflections, personaVersions } = data
  // 改过号就用改完的那一份(后端回的是改动后的配额), 没改过就用加载时读到的
  const hv = handleView ?? data.handle

  /** 所有写操作共用的壳: 清消息、给出错、跑动作、重拉 */
  async function act(fn: () => Promise<void>, ok: string) {
    setBusy(true)
    setMsg('')
    setActError('')
    try {
      await fn()
      setMsg(ok)
      reload()
    } catch (e) {
      setActError(e instanceof Error ? e.message : '操作失败')
    } finally {
      setBusy(false)
    }
  }

  async function updatePersona() {
    if (!description.trim()) return
    await act(async () => {
      await agentApi.updatePersona(id, description.trim())
      setDescription('')
    }, '人格已更新, 它以新的方式理解世界。')
  }

  /**
   * 改账号ID。**刻意不走 `act()`** —— 那个壳把所有失败压成一句字符串, 而这里
   * 400/409/429 各自带着一句只能由后端给出的话(撞上的是哪个号、什么时候能再改)。
   * 压成一句"修改失败"就等于把这三件事重新变成同一件事, 那正是这个功能要避免的。
   */
  async function changeHandle() {
    const wanted = handleInput.trim()
    if (!wanted) return
    setHandleBusy(true)
    setHandleFailure(null)
    setHandleOk('')
    try {
      const next = await agentApi.updateHandle(id, wanted)
      setHandleView(next)
      setHandleInput('')
      setHandleOk(`账号ID 已改为 ${next.handle}，今年还剩 ${next.remaining} 次修改机会。`)
      // 通讯录与聊天列表的账号ID 都来自 companion store 那份缓存 —— 不就地改它,
      // 返回列表时看到的还是旧号(而库里已经是新的了)
      patchCompanion(id, { handle: next.handle })
    } catch (e) {
      setHandleFailure(describeFailure(e))
    } finally {
      setHandleBusy(false)
    }
  }

  async function exportMemories() {
    // 导出是纯读, 不该走 act —— 失败时也不该把整页变成错误态
    try {
      const payload = await agentApi.exportMemories(id)
      const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `${agent.name || 'agent'}-memories.json`
      a.click()
      URL.revokeObjectURL(url)
    } catch (e) {
      setActError(e instanceof Error ? e.message : '导出失败')
    }
  }

  function clearMemories() {
    if (!confirm('确定清空它对你的所有记忆吗?这是不可逆的。')) return
    void act(async () => {
      await agentApi.clearMemories(id)
    }, '它的记忆已清空。')
  }

  function clearUserModel() {
    if (!confirm('确定让它忘掉对你的所有了解吗?这是不可逆的。')) return
    void act(async () => {
      await agentApi.clearUserModel(id)
    }, '它对你的了解已清空。')
  }

  function deleteAgent() {
    if (!confirm(`确定删除 ${agent.name} 吗?它会永远消失, 你们聊过的一切都不会留下。`)) return
    void act(async () => {
      await agentApi.removeAgent(id)
      // 通讯录的 store 里也要摘掉, 否则返回通讯录时它还挂在那里, 点进去 404
      removeCompanion(id)
      navigate('/contacts', { replace: true })
    }, '')
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <SettingsHeader name={`设置 · ${agent.name}`} onBack={() => navigate(`/contacts/agent/${id}`)} />

      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto w-full max-w-2xl space-y-6 px-5 py-6">
          {msg && (
            <p className="rounded-xl border border-ok/30 bg-ok/10 px-4 py-2 text-sm text-ok">{msg}</p>
          )}
          {actError && <PanelError message={actError} />}

          <section className="card p-5">
            <div className="flex items-center gap-4">
              <Avatar name={agent.name} kind="agent" size={56} />
              <div className="min-w-0">
                <h2 className="truncate text-xl font-semibold tracking-tight text-ink">
                  {agent.name}
                </h2>
                <p className="mt-1 text-sm text-ink-soft">
                  {agent.age !== null && agent.age !== undefined ? `${agent.age} 岁` : ''}
                  {agent.age !== null && agent.age !== undefined && agent.birthDate ? ' · ' : ''}
                  {agent.birthDate ? `生日 ${agent.birthDate}` : ''}
                </p>
                {agent.nextBirthday && (
                  <p className="text-xs text-ink-faint tnum">
                    下一个生日:{format(new Date(agent.nextBirthday), 'M月d日')}
                  </p>
                )}
              </div>
            </div>
          </section>

          <section className="card p-5">
            <PanelSection
              title="账号ID"
              hint="名字可以重复 —— 它是从你的描述里生成的, 相似的描述会得到同一个名字。账号ID 唯一, 用来区分同名的人, 也可以报给别人。"
            >
              <div className="flex items-baseline gap-2 rounded-xl border border-line bg-sunken px-3 py-2.5">
                <span className="text-xs text-ink-faint">当前</span>
                <span className="select-all font-mono text-sm text-ink">
                  {hv.handle ?? '（还没有分配）'}
                </span>
                <span className="ml-auto shrink-0 text-xs text-ink-faint tnum">
                  {quotaLabel(hv)}
                </span>
              </div>

              <div className="flex flex-wrap items-center gap-2">
                <input
                  className="input min-w-0 flex-1 font-mono"
                  placeholder="新的账号ID，例如 xiaoman"
                  value={handleInput}
                  // 一年三次用完时连输入框一起停掉 —— 让用户敲完再撞一次墙是纯粹的浪费
                  disabled={handleBusy || hv.remaining <= 0}
                  onChange={(e) => setHandleInput(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') void changeHandle()
                  }}
                />
                <button
                  type="button"
                  className="btn-primary"
                  onClick={() => void changeHandle()}
                  disabled={handleBusy || hv.remaining <= 0 || !handleInput.trim()}
                >
                  {handleBusy ? '正在修改…' : '修改账号ID'}
                </button>
              </div>

              {handleOk && (
                <p className="rounded-xl border border-ok/30 bg-ok/10 px-3 py-2 text-sm text-ok">
                  {handleOk}
                </p>
              )}

              {/*
                失败时把后端的 `hint` 一起显示出来 —— 那句话里有只有后端知道的东西
                (撞上的是哪个号、什么时候滑出窗口), 前端再写一张映射表只会让它过期。
                见 `lib/handles.ts` 的 describeFailure。
              */}
              {handleFailure && (
                <p className="rounded-xl border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger">
                  {handleFailure.message}
                  {handleFailure.hint && (
                    <span className="mt-0.5 block text-xs text-danger/80">{handleFailure.hint}</span>
                  )}
                </p>
              )}

              <p className="text-xs text-ink-faint">
                只能用字母、数字、下划线(_)和短横线(-)，以字母开头, 6-24 个字符。大写会自动转成小写。
              </p>
            </PanelSection>
          </section>

          <section className="card p-5">
            <PanelSection title="重新描述它" hint="它的性格会重新编译成一个新版本, 旧版本会保留在下面">
              <textarea
                className="input min-h-28 resize-none"
                placeholder="描述你想要的它…"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
              />
              <button
                type="button"
                className="btn-primary"
                onClick={() => void updatePersona()}
                disabled={busy || !description.trim()}
              >
                <Sparkles size={15} />
                {busy ? '正在重新认识…' : '更新人格'}
              </button>
            </PanelSection>
          </section>

          <section className="card p-5">
            <PanelSection title="人格版本历史" hint="每次演化都留一份, 看得出它是怎么变成现在的样子的">
              {personaVersions.length === 0 && (
                <p className="text-sm text-ink-faint">还没有版本记录。</p>
              )}
              {personaVersions.map((v) => (
                <div key={v.id} className="rounded-xl border border-line bg-sunken px-3 py-2.5">
                  <div className="flex items-center gap-2 text-sm">
                    <span className="chip bg-accent-soft text-accent tnum">v{v.version}</span>
                    {v.active && <span className="chip bg-ok/15 text-ok">当前</span>}
                    <span className="ml-auto text-xs text-ink-faint tnum">
                      {format(new Date(v.createdAt), 'M月d日 HH:mm')}
                      {changeSourceZh(v.changeSource) ? ` · ${changeSourceZh(v.changeSource)}` : ''}
                    </span>
                  </div>
                  {v.changeReason && <p className="mt-1 text-xs text-ink-soft">{v.changeReason}</p>}
                </div>
              ))}
            </PanelSection>
          </section>

          <section className="card p-5">
            <PanelSection title="它的复盘" hint="每天凌晨它会对自己经历做一次总结">
              {reflections.length === 0 && (
                <p className="text-sm text-ink-faint">还没有反思记录。</p>
              )}
              {reflections.map((r) => (
                <div key={r.id} className="rounded-xl border border-line bg-sunken px-3 py-2.5">
                  <div className="flex items-center gap-2 text-xs">
                    <span className="chip bg-accent-soft text-accent">
                      {r.type === 'daily' ? '每日' : r.type === 'weekly' ? '每周' : r.type}
                    </span>
                    <span className="text-ink-faint tnum">{r.period}</span>
                    {r.insights && r.insights.length > 0 && (
                      <span className="ml-auto text-ink-faint tnum">{r.insights.length} 条洞察</span>
                    )}
                  </div>
                  {r.summary && <p className="mt-1.5 text-sm text-ink-soft">{r.summary}</p>}
                  {r.insights && r.insights.length > 0 && (
                    <ul className="mt-1.5 space-y-0.5">
                      {r.insights.slice(0, 4).map((ins, i) => (
                        <li key={i} className="flex gap-1.5 text-xs text-ink-soft">
                          <span className="mt-1.5 marker-dot shrink-0 bg-accent" />
                          {String(ins)}
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              ))}
            </PanelSection>
          </section>

          <section className="card p-5">
            <PanelSection title="人生时间线" hint="它来到这个世界之前的那段人生">
              {lifeEvents.length === 0 && <p className="text-sm text-ink-faint">还没有经历。</p>}
              {lifeEvents.map((e, i) => (
                <div key={e.id} className="relative flex gap-3 pb-4">
                  {i < lifeEvents.length - 1 && (
                    <span className="absolute left-1.5 top-4 h-full w-px bg-line" />
                  )}
                  <span className="marker-dot mt-1.5 shrink-0 bg-accent" />
                  <div className="min-w-0">
                    <div className="text-sm text-ink">{e.title}</div>
                    <div className="text-xs text-ink-faint">
                      {e.startTime ? format(new Date(e.startTime), 'yyyy') : ''}
                      {e.endTime ? ` - ${format(new Date(e.endTime), 'yyyy')}` : ''}
                      {e.description ? ` · ${e.description}` : ''}
                    </div>
                  </div>
                </div>
              ))}
            </PanelSection>
          </section>

          <section className="card p-5">
            <PanelSection title="记忆与数据" hint="记忆可以导出为 JSON, 也可以随时遗忘">
              <div className="flex flex-wrap gap-2">
                <button type="button" className="btn-ghost" onClick={() => void exportMemories()}>
                  <Download size={15} />
                  导出记忆
                </button>
                <button type="button" className="btn-danger" onClick={clearMemories} disabled={busy}>
                  清空记忆
                </button>
                <button type="button" className="btn-danger" onClick={clearUserModel} disabled={busy}>
                  清空它对你的了解
                </button>
              </div>
            </PanelSection>
          </section>

          <section className="card border-danger/30 p-5">
            <PanelSection title="危险区" hint="删除后无法恢复, 它会永远消失">
              <p className="text-sm text-ink-soft">
                它的记忆、人格、与你的关系都会一并删除。想留着的话, 先把记忆导出去。
              </p>
              <button type="button" className="btn-danger" onClick={deleteAgent} disabled={busy}>
                <Trash2 size={15} />
                删除 {agent.name}
              </button>
            </PanelSection>
          </section>
        </div>
      </div>
    </div>
  )
}

function SettingsHeader({ name, onBack }: { name: string; onBack: () => void }) {
  return (
    <header className="z-10 flex items-center gap-1 border-b border-line bg-surface/90 px-1.5 py-1.5 backdrop-blur">
      <button
        type="button"
        onClick={onBack}
        title="返回"
        className="grid h-9 w-9 shrink-0 place-items-center rounded-lg text-ink-soft transition-colors hover:bg-sunken hover:text-ink"
      >
        <ChevronLeft size={20} />
      </button>
      <span className="min-w-0 flex-1 truncate text-sm font-medium text-ink">{name}</span>
    </header>
  )
}
