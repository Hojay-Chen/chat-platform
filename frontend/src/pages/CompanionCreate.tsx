import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ArrowLeft, Sparkles } from 'lucide-react'
import { api } from '@/api/client'
import { createAgentFriend, newRequestId, type ProvisionedAgentFriend } from '@/api/agentFriends'
import { Avatar } from '@/components/im/Avatar'
import { NameWithHandle } from '@/components/im/NameWithHandle'
import { useCompanionStore } from '@/stores/companion'
import { creationFailure, pairingNotice } from '@/lib/agentFriends'
import { RELATIONSHIP_TYPES, relationshipTypeZh, type RelationshipTypeValue } from '@/lib/relationships'
import type { Persona } from '@/types'

const TRAIT_ZH: Record<string, string> = {
  warmth: '温柔',
  maturity: '成熟',
  independence: '独立',
  playfulness: '活泼',
  curiosity: '好奇',
  confidence: '自信',
  patience: '耐心',
  sociability: '外向',
  emotionalSensitivity: '敏感',
  rationality: '理性',
}

const DEFAULT_SCENARIOS = [
  { label: '工作失败', text: '用户今天工作失败了,有点沮丧地跟你说了这件事。' },
  { label: '深夜疲惫', text: '用户深夜发消息说,最近加班太累了。' },
  { label: '分享喜悦', text: '用户开心地告诉你,他通过了重要的面试。' },
  { label: '被误解', text: '用户有点委屈地说,今天被人误会了。' },
]

export default function CompanionCreate() {
  const navigate = useNavigate()
  const reloadContacts = useCompanionStore((s) => s.load)
  const [description, setDescription] = useState('')
  const [compiling, setCompiling] = useState(false)
  const [persona, setPersona] = useState<Persona | null>(null)
  const [preview, setPreview] = useState('')
  const [scenario, setScenario] = useState(DEFAULT_SCENARIOS[0].text)
  const [previewing, setPreviewing] = useState(false)
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState('')
  const [errorHint, setErrorHint] = useState<string | null>(null)
  // §七: 用户显式选择的关系类型(Agent 世界中的真实关系状态)
  const [relationshipType, setRelationshipType] = useState<RelationshipTypeValue | ''>('')
  /** 建成之后的回执 —— 非空时整页换成"已完成"那一屏 */
  const [created, setCreated] = useState<ProvisionedAgentFriend | null>(null)

  /*
   * 幂等键 —— **整个一键创建里最不能出错的那个值**。
   *
   * 同一个 requestId 重放两次不会铸出第二个聊天账号, 也不会建出第二个 agent; 换一个
   * requestId 就会 —— 而多出来的那一份**不违反任何约束**, 它只是一行永远没人用的
   * `users` 加一台永远配不上的设备。也就是说这个 bug 不会以任何形式报错, 只会让用户的
   * 通讯录里慢慢多出几个连不上话的幽灵好友。
   *
   * 所以它的生命周期必须与**意图**一致, 而"意图"在这里就是"我想造这个人":
   *
   * - **懒铸**: 点「创建」时才生成。在那之前用户改描述、改关系都是免费的 —— 还没有键。
   * - **重试复用**: 失败之后再点一次走的是同一个键。这正是"建到一半失败了"能回到同一个
   *   账号上的原因(后端把上一次的设备吊销了, 同一个键会**复活**它而不是另铸一个)。
   * - **重新描述时换新**: 人格换了就是另一个人了, 这时复用旧键会拿回上一个 agent。
   *
   * **改「关系」不换键**, 这一条是有取舍的: 失败之后改关系再重试, 拿回来的 agent 仍然是
   * 第一次那个关系(后端按 chat_account_id 幂等, 重放不会更新它)。选了"关系可能是旧的"
   * 而不是"可能多一个幽灵好友" —— 前者用户看得见也能再改, 后者他根本不知道发生了什么。
   */
  const requestIdRef = useRef<string | null>(null)

  /** 回到"还没开始建"的状态, 并让下一次创建换一个幂等键 */
  function resetIntent() {
    requestIdRef.current = null
    setError('')
    setErrorHint(null)
  }

  async function compile() {
    if (!description.trim()) return
    setCompiling(true)
    // 编译出一个新人格 = 换了一个人, 于是上一次的幂等键到此为止(见 requestIdRef 那段)
    resetIntent()
    try {
      const resp = await api.post<{ persona: Persona; preview: string }>('/api/companions/compile', {
        description: description.trim(),
      })
      setPersona(resp.persona)
      setPreview(resp.preview)
      setScenario(DEFAULT_SCENARIOS[0].text)
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setCompiling(false)
    }
  }

  async function rePreview() {
    if (!persona) return
    setPreviewing(true)
    setError('')
    try {
      const resp = await api.post<{ response: string }>('/api/companions/preview', { persona, scenario })
      setPreview(resp.response)
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setPreviewing(false)
    }
  }

  /**
   * 「就它了」—— 四步编排的那一次调用。
   *
   * <h2>它和这里原先那一行的区别, 就是这一期需求的全部分量</h2>
   *
   * 原先打的是 `POST /api/companions`: 8091 建一个 agent 就结束了, **没有聊天账号** ——
   * 那个 agent 会出现在通讯录里, 但它在聊天平台上没有身份, 说不了话。走出来的是"一个
   * agent"。
   *
   * 现在打 `POST /api/agent-friends`: 聊天平台先铸一个聊天账号, 再拿这个账号的 id 去
   * agent 平台的 openAPI 登记 agent, 然后回绑设备、建出会话。走出来的是"一个好友"。
   */
  async function create() {
    if (!persona) return
    setCreating(true)
    setError('')
    setErrorHint(null)
    // 懒铸: 第一次点创建时才有键, 之后的重试一路复用它(见 requestIdRef 那段)
    if (!requestIdRef.current) requestIdRef.current = newRequestId()
    try {
      const friend = await createAgentFriend({
        requestId: requestIdRef.current,
        description: description.trim() || undefined,
        persona,
        relationshipType: relationshipType || undefined,
      })
      // 通讯录与聊天列表都是从 `/api/companions` 拉的, 而刚建出来的这位在**两个**列表里
      // 都该出现 —— 所以重拉一次, 而不是只往本地塞一行。这条链在后面还建了一个会话
      // (好友要出现在聊天列表里), 那是本地拼不出来的。
      await reloadContacts()
      setCreated(friend)
    } catch (err) {
      const f = creationFailure(err)
      setError(f.message)
      setErrorHint(f.hint)
    } finally {
      setCreating(false)
    }
  }

  const name = persona?.identity?.name || '新 Agent'
  const traits = persona?.personality?.traits || {}

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
      <header className="border-b border-line">
        <div className="mx-auto flex max-w-3xl items-center gap-3 px-5 py-4">
          <button onClick={() => navigate('/contacts')} className="btn-ghost !px-3 !py-1.5">
            <ArrowLeft size={15} />
          </button>
          <span className="text-lg text-ink">添加 Agent</span>
        </div>
      </header>

      <main className="mx-auto max-w-3xl px-5 py-8">
        {created ? (
          <CreatedPanel
            friend={created}
            onSeeIt={() => navigate(`/contacts/agent/${created.agentId}`, { replace: true })}
            onAgain={() => {
              // 再建一个是**另一个意图** —— 人格、描述、幂等键全部从头来
              resetIntent()
              setCreated(null)
              setPersona(null)
              setPreview('')
              setDescription('')
              setRelationshipType('')
            }}
          />
        ) : (
          <>
        {error && (
          <div className="mb-4 rounded-xl border border-danger/30 bg-danger/10 px-4 py-2 text-sm text-danger">
            <p>{error}</p>
            {/* 后端那句 hint 里有只有它知道的信息(要不要重试、往哪看)。第一句说的是
                "出了什么事", 这一句说的是"现在能做什么" —— 少了它, 用户面对一个
                他无法判断严重程度的故障 */}
            {errorHint && <p className="mt-1 text-xs text-danger/80">{errorHint}</p>}
          </div>
        )}

        {/* Step 1: 描述 */}
        <section className="card p-6">
          <div className="mb-1 text-xs uppercase tracking-widest text-accent">STEP 1</div>
          <h2 className="text-2xl text-ink">用你的话描述它</h2>
          <p className="mt-1 text-sm text-ink-soft">
            性格、说话方式、你们的关系、它想要怎样的相处。想到什么说什么,它会从你的描述里诞生。
          </p>
          <textarea
            className="input mt-4 min-h-32 resize-none"
            placeholder="例如:我想要一个比我成熟一点的伙伴,温柔但不黏人,有自己的生活,平时活泼一点,偶尔会调侃我。我不开心的时候希望它先陪我,不要一直讲大道理。"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
          <button className="btn-primary mt-4" onClick={compile} disabled={compiling || !description.trim()}>
            <Sparkles size={16} />
            {compiling ? '正在读懂你…' : '编译人格'}
          </button>
        </section>

        {/* Step 1.5: 你和它是什么关系 (§七: 真实关系状态, 不是 Prompt) */}
        {persona && (
          <section className="card mt-6 p-6 animate-fadeUp">
            <div className="mb-1 text-xs uppercase tracking-widest text-accent">你们的关系</div>
            <h2 className="text-xl text-ink">你和它是什么关系?</h2>
            <p className="mt-1 text-sm text-ink-soft">
              这会成为它世界里真实的关系状态:它对你的熟悉、信任、亲昵都会从它开始。
            </p>
            <div className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-3">
              {RELATIONSHIP_TYPES.map((t) => (
                <button
                  key={t.value}
                  onClick={() => setRelationshipType(t.value as RelationshipTypeValue)}
                  className={`rounded-xl border px-3 py-2.5 text-left transition ${
                    relationshipType === t.value
                      ? 'border-accent/60 bg-accent-soft'
                      : 'border-line bg-sunken hover:border-line-strong'
                  }`}
                >
                  <div className={`text-sm ${relationshipType === t.value ? 'text-accent' : 'text-ink'}`}>
                    {t.label}
                  </div>
                  <div className="mt-0.5 text-[11px] leading-snug text-ink-faint">{t.desc}</div>
                </button>
              ))}
            </div>
          </section>
        )}

        {/* Step 2: 预览 */}
        {persona && (
          <section className="card mt-6 p-6 animate-fadeUp">
            <div className="mb-1 text-xs uppercase tracking-widest text-accent">STEP 2 · 预览</div>
            <div className="mt-3 flex items-center gap-4">
              <Avatar name={name} kind="agent" size={56} />
              <div>
                <h3 className="text-2xl text-ink">{name}</h3>
                <p className="text-sm text-ink-soft">
                  {persona.identity?.gender === 'male' ? '男性' : '女性'} ·{' '}
                  {relationshipType
                    ? relationshipTypeZh(relationshipType)
                    : persona.relationship?.type
                      ? relationshipTypeZh(persona.relationship.type)
                      : '朋友'}
                </p>
              </div>
            </div>

            {persona.personality?.summary && (
              <p className="mt-4 text-sm leading-relaxed text-ink-soft">{persona.personality.summary}</p>
            )}

            {Object.keys(traits).length > 0 && (
              <div className="mt-4 grid grid-cols-2 gap-x-6 gap-y-2">
                {Object.entries(traits).map(([key, val]) => (
                  <div key={key} className="flex items-center gap-2 text-xs">
                    <span className="w-8 text-ink-soft">{TRAIT_ZH[key] || key}</span>
                    <div className="h-1 flex-1 overflow-hidden rounded-full bg-sunken">
                      <div
                        className="h-full rounded-full bg-accent"
                        style={{ width: `${Math.round((val || 0) * 100)}%` }}
                      />
                    </div>
                    <span className="w-7 text-right text-ink-faint">{Math.round((val || 0) * 100)}</span>
                  </div>
                ))}
              </div>
            )}

            {persona.communication?.style && (
              <p className="mt-4 text-sm text-ink-soft">说话方式:{persona.communication.style}</p>
            )}

            {/* 场景预览 */}
            <div className="mt-6 rounded-xl border border-line bg-sunken p-4">
              <p className="text-xs text-ink-faint">场景:{scenario}</p>
              <div className="mt-3 flex gap-2 flex-wrap">
                {DEFAULT_SCENARIOS.map((s) => (
                  <button
                    key={s.label}
                    onClick={() => {
                      setScenario(s.text)
                      setPreview('')
                    }}
                    className={`chip border transition ${
                      scenario === s.text
                        ? 'border-accent/50 bg-accent-soft text-accent'
                        : 'border-line text-ink-soft hover:border-line-strong'
                    }`}
                  >
                    {s.label}
                  </button>
                ))}
              </div>
              <button className="btn-ghost mt-3 !px-3 !py-1.5 text-xs" onClick={rePreview} disabled={previewing}>
                {previewing ? '正在想…' : '换一种回应看看'}
              </button>
              {preview && (
                <div className="mt-4 rounded-xl border border-line bg-sunken px-4 py-3 text-sm text-ink animate-fadeUp">
                  {preview}
                </div>
              )}
            </div>

            <div className="mt-6 flex items-center justify-between">
              <button
                className="btn-outline"
                onClick={() => {
                  setPersona(null)
                  setPreview('')
                  // 丢掉这个人格 = 丢掉这一次意图, 连同它的幂等键(见 requestIdRef 那段)
                  resetIntent()
                }}
              >
                重新描述
              </button>
              <button className="btn-primary" onClick={create} disabled={creating}>
                {creating ? '正在唤醒…' : '就它了,开始相处'}
              </button>
            </div>
          </section>
        )}
          </>
        )}
      </main>
    </div>
  )
}

/**
 * 建成之后那一屏 —— 四步走完之后你手里有什么。
 *
 * <h2>为什么要有这一屏, 而不是建完直接跳走</h2>
 *
 * 因为**配对码只在这里出现过**。后端在 `completePairing` 之后会把
 * `simulator_devices.pairing_code` 置空, 也就是说离开这一屏之后, 那串码没有任何地方
 * 能再查到(唯一能拿回它的办法是拿同一个 `requestId` 重放一次请求, 而那个键只活在上一个
 * 屏幕的 `useRef` 里)。直接跳走 = 用户永远拿不到他要交给 Agent 程序的那串码。
 *
 * <h2>为什么把账号ID 摆在名字旁边</h2>
 *
 * 因为这一屏是"你的好友建成了"这句话的证据所在。而两个 agent 可以同名 —— 用户那 7 个
 * 「小满」就是这么来的。`agent_xxx` 是唯一能回答"刚才建的是哪一个"的东西, 所以它和名字
 * 一样是主角, 不是脚注。
 *
 * <p>三个 id 里只显示这一个: `agentId` 与 `chatAccountId` 都是给程序看的, 摆在用户面前
 * 只会让人以为"这三个哪个才是我要找的"。它们仍在回执里, 需要时从网络面板能看到。
 */
function CreatedPanel({
  friend,
  onSeeIt,
  onAgain,
}: {
  friend: ProvisionedAgentFriend
  onSeeIt: () => void
  onAgain: () => void
}) {
  const notice = pairingNotice(friend)
  const name = friend.name || '新 Agent'

  return (
    <section className="card animate-fadeUp p-6">
      <div className="mb-1 text-xs uppercase tracking-widest text-accent">已完成</div>
      <h2 className="text-2xl text-ink">它已经住进来了</h2>
      <p className="mt-1 text-sm text-ink-soft">
        一个聊天账号、一个 Agent、一段会话 —— 三样一起建好了。它现在同时出现在你的通讯录和聊天列表里。
      </p>

      <div className="mt-6 flex items-center gap-4">
        <Avatar name={name} kind="agent" size={56} />
        <div className="min-w-0">
          <NameWithHandle
            name={name}
            handle={friend.handle}
            className="text-xl font-semibold text-ink"
          />
          <p className="mt-0.5 text-xs text-ink-faint">这个名字可以重复, 上面那串账号ID 不会</p>
        </div>
      </div>

      {/* 码要能被一眼读出来、一次选中。`tracking` 是让六个字符不糊成一团,
          `select-all` 是让一次点击就选中整串 —— 它是要被抄到另一个程序里去的 */}
      <div className="mt-6 rounded-xl border border-line bg-sunken p-4">
        <p className="text-xs uppercase tracking-widest text-ink-faint">{notice.title}</p>
        {notice.code && (
          <p className="mt-2 select-all font-mono text-3xl tracking-[0.35em] text-ink">
            {notice.code}
          </p>
        )}
        <p className="mt-2 text-xs leading-relaxed text-ink-soft">{notice.detail}</p>
      </div>

      <div className="mt-6 flex items-center justify-between">
        <button className="btn-outline" onClick={onAgain}>
          再建一个
        </button>
        <button className="btn-primary" onClick={onSeeIt}>
          看看它
        </button>
      </div>
    </section>
  )
}
