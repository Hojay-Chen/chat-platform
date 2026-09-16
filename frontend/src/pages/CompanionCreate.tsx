import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ArrowLeft, Sparkles } from 'lucide-react'
import { api } from '@/api/client'
import { Avatar } from '@/components/im/Avatar'
import { useCompanionStore } from '@/stores/companion'
import { RELATIONSHIP_TYPES, relationshipTypeZh, type RelationshipTypeValue } from '@/lib/relationships'
import type { Companion, Persona } from '@/types'

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
  const addCompanion = useCompanionStore((s) => s.add)
  const [description, setDescription] = useState('')
  const [compiling, setCompiling] = useState(false)
  const [persona, setPersona] = useState<Persona | null>(null)
  const [preview, setPreview] = useState('')
  const [scenario, setScenario] = useState(DEFAULT_SCENARIOS[0].text)
  const [previewing, setPreviewing] = useState(false)
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState('')
  // §七: 用户显式选择的关系类型(Agent 世界中的真实关系状态)
  const [relationshipType, setRelationshipType] = useState<RelationshipTypeValue | ''>('')

  async function compile() {
    if (!description.trim()) return
    setCompiling(true)
    setError('')
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

  async function create() {
    if (!persona) return
    setCreating(true)
    setError('')
    try {
      const c = await api.post<Companion>('/api/companions', {
        persona,
        relationshipType: relationshipType || undefined,
      })
      addCompanion(c)
      navigate(`/contacts/agent/${c.id}`, { replace: true })
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setCreating(false)
    }
  }

  const name = persona?.identity?.name || '她'
  const traits = persona?.personality?.traits || {}

  return (
    <div className="min-h-screen bg-surface">
      <header className="border-b border-line">
        <div className="mx-auto flex max-w-3xl items-center gap-3 px-5 py-4">
          <button onClick={() => navigate('/contacts')} className="btn-ghost !px-3 !py-1.5">
            <ArrowLeft size={15} />
          </button>
          <span className="text-lg text-ink">创建新伴侣</span>
        </div>
      </header>

      <main className="mx-auto max-w-3xl px-5 py-8">
        {error && <p className="mb-4 rounded-xl border border-danger/30 bg-danger/10 px-4 py-2 text-sm text-danger">{error}</p>}

        {/* Step 1: 描述 */}
        <section className="card p-6">
          <div className="mb-1 text-xs uppercase tracking-widest text-accent">STEP 1</div>
          <h2 className="text-2xl text-ink">用你的话描述她</h2>
          <p className="mt-1 text-sm text-ink-soft">
            性格、说话方式、你们的关系、她想要怎样的相处。想到什么说什么,她会从你的描述里诞生。
          </p>
          <textarea
            className="input mt-4 min-h-32 resize-none"
            placeholder="例如:我想要一个比我成熟一点的女生,温柔但不黏人,有自己的生活,平时活泼一点,偶尔会调侃我。我不开心的时候希望她先陪我,不要一直讲大道理。"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
          <button className="btn-primary mt-4" onClick={compile} disabled={compiling || !description.trim()}>
            <Sparkles size={16} />
            {compiling ? '正在读懂你…' : '编译人格'}
          </button>
        </section>

        {/* Step 1.5: 你和她是什么关系 (§七: 真实关系状态, 不是 Prompt) */}
        {persona && (
          <section className="card mt-6 p-6 animate-fadeUp">
            <div className="mb-1 text-xs uppercase tracking-widest text-accent">你们的关系</div>
            <h2 className="text-xl text-ink">你和她是什么关系?</h2>
            <p className="mt-1 text-sm text-ink-soft">
              这会成为她世界里真实的关系状态:她对你的熟悉、信任、亲昵都会从它开始。
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
                }}
              >
                重新描述
              </button>
              <button className="btn-primary" onClick={create} disabled={creating}>
                {creating ? '正在遇见…' : '就她了,开始相处'}
              </button>
            </div>
          </section>
        )}
      </main>
    </div>
  )
}
