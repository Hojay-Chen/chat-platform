import { ApiError } from '@/api/client'

/**
 * 一键创建结果在界面上要说的那几句话 —— 纯函数。
 *
 * <h2>为什么在 `lib/` 而不是写在 `CompanionCreate.tsx` 里</h2>
 *
 * 单测跑在 `environment: 'node'` 下, 没有 jsdom —— 带 `useState` 的组件在测试里渲染不
 * 起来, 于是写在组件里的分支永远不会有断言。这个文件和 `lib/handles.ts`、
 * `lib/conversations.ts` 是同一条规矩。
 *
 * <p>而这里确实有分支要守: **配对码只在这一个屏幕上出现过, 之后再也拿不到**
 * (库里存的是它的 hash 用途之外的另一个东西 —— `completePairing` 之后
 * `pairing_code` 被置空; 重试能拿回同一个码, 但那要求调用方还捏着同一个 `requestId`)。
 * 也就是说这几行字是用户唯一一次机会知道那串码是干什么用的。措辞错了没有任何地方会报错,
 * 只会在几天后表现为"我那个 Agent 连不上"。
 */

/**
 * 只要求"可能有配对码"这几个字段, 不绑死 `ProvisionedAgentFriend` ——
 * 与 `lib/handles.ts` 的 `HandleBearing` 同一写法: 纯函数不该为了一个字段去依赖一整份契约。
 */
export interface PairingBearing {
  /** 一次性配对码。null/undefined = 这台设备已经配对过了, **不是失败** */
  pairingCode?: string | null
  /** ISO 本地时间(`2026-09-17T21:10:32.123`), 与后端 `LocalDateTime.toString()` 同形 */
  pairingCodeExpiresAt?: string | null
}

export interface PairingNotice {
  title: string
  detail: string
  /** 要原样显示的那串码。null = 这次没有码可显示, 界面不该画一个空框 */
  code: string | null
}

/**
 * 「配对码」那一块的两行字。
 *
 * <h2>为什么有效时间是从 `expiresAt` 算出来的, 不是写死"10 分钟"</h2>
 *
 * 因为那个 10 是 `app.simulator.pairing-code-ttl-minutes` 的默认值, 而它是个可配项。
 * 写死之后, 一旦有人把它调成 30, 界面就会开始撒谎 —— 而谎话的方向恰好是危险的那一侧
 * (告诉用户"10 分钟内有效", 让他以为码已经过期了而重新创建, 于是多出一个账号)。
 * 从 `expiresAt` 读时钟时间则永远是实话, 而且对用户更有用: 他要知道的是"到几点为止"。
 *
 * <h2>没有码的那条分支为什么不是一句空话</h2>
 *
 * 它只在**重试命中一台已经配对的设备**时出现(后端 `reuseExisting` 的 ACTIVE 分支)。
 * 从界面上看那是"我点了第二次、它说建好了但没有码", 最容易被理解成失败。
 * 所以那句话要说的正是"这不是失败": 账号和 Agent 都没有重建, 也本来就没什么要配的。
 */
export function pairingNotice(receipt: PairingBearing): PairingNotice {
  const code = receipt.pairingCode?.trim()
  if (!code) {
    return {
      title: '这台设备已经配对过了',
      detail: '这次操作复用了上一次的创建 —— 聊天账号与 Agent 都没有重建, 也不需要再配一次。',
      code: null,
    }
  }

  const clock = clockOf(receipt.pairingCodeExpiresAt)
  return {
    title: '配对码',
    detail: clock
      ? `把它交给你要运行这个 Agent 的程序, 它凭这串码换取长期凭据。${clock} 之前有效, 且只在这里显示这一次。`
      : '把它交给你要运行这个 Agent 的程序, 它凭这串码换取长期凭据。这串码只在这里显示这一次。',
    code,
  }
}

/**
 * 从 ISO 本地时间里取 `HH:mm`。
 *
 * <p>不 `new Date(...)` —— 那个函数会把无时区的字符串按**浏览器本地时区**解释, 而这里的
 * 值是服务器时钟。两者恰好在同一台机器上是巧合, 在别的部署里就是几个小时的偏差。
 * 直接切字符串则是在说"这串字符的第 12-16 位" —— 对无时区的本地时间, 那正是它的时钟读数。
 *
 * <p>形状不对就返回 null, 由调用方退回一句不需要时间的措辞 —— 而不是画一个 `NaN:NaN`。
 */
function clockOf(iso: string | null | undefined): string | null {
  if (!iso) return null
  const t = iso.indexOf('T')
  if (t === -1) return null
  const clock = iso.slice(t + 1, t + 6)
  return /^\d{2}:\d{2}$/.test(clock) ? clock : null
}

export interface CreationFailure {
  /** 该显示为错误主体的那句话 */
  message: string
  /** 后端给的可执行下一步; 没有时 null */
  hint: string | null
}

/**
 * 创建失败时给用户看的两行字。
 *
 * <h2>为什么要把 `hint` 单独取出来, 而不是只显示 `message`</h2>
 *
 * 因为这个端点的失败里有一类是**用户此刻能采取行动的**: Agent 平台不可达时后端回的
 * `hint` 是"稍后再试"。只显示 `message`("Agent 平台暂不可达")的话, 用户读到的是一个
 * 他无法判断严重程度的故障 —— 而正确的动作(过一会儿用同一个 `requestId` 再点一次,
 * 会走回同一个账号)恰恰没人告诉他。
 *
 * <p>与 `lib/handles.ts` 的 `describeFailure` 同一先例: 读后端的 hint, 不自己写一张
 * 状态码映射表。后端那两句话里有只有它知道的信息(哪个地址不可达、要不要重试)。
 *
 * <p>非 {@link ApiError}(网络断了、`fetch` 自己抛的 TypeError)走 `message`,
 * 那种情况下没有 hint 可用, 也不该编一个。
 */
export function creationFailure(e: unknown): CreationFailure {
  if (e instanceof ApiError) return { message: e.message, hint: e.hint }
  return { message: e instanceof Error ? e.message : '创建失败', hint: null }
}
