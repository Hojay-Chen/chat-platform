import { ApiError } from '@/api/client'

/**
 * 账号ID 在界面上的那几件纯事 —— 索引、配额措辞、失败措辞。
 *
 * <h2>为什么在 `lib/` 而不是写在组件里</h2>
 *
 * 单测跑在 `environment: 'node'` 下, 只有 `renderToStaticMarkup`, 没有 jsdom ——
 * 带 `useState`/`useEffect` 的组件在测试里根本渲染不起来。所以凡是"能算出来"的东西
 * 都必须住在组件外面, 否则它永远不会有断言。这个文件和 `lib/conversations.ts`、
 * `lib/time.ts` 是同一条规矩。
 */

/** 只要求「有 id, 可能有 handle」—— 不绑死 `Companion`, 因为会话行那边传进来的是别的形状 */
export interface HandleBearing {
  id: string
  handle?: string | null
}

/**
 * companionId → 账号ID 的索引, 用来把 `/api/companions` 的账号ID 拼到会话行上。
 *
 * <h2>这一步为什么必须在前端做</h2>
 *
 * 账号ID 住在仓 2 的 `persons` 表, 而会话列表由仓 1 的 8081 直接从
 * `conversation_participants.display_name` 拼出来(一个本地列, 零跨服务调用)。
 * 要让它带账号ID, 8081 就得为**每一行**回头去问一次 8091 —— 一个列表页里最不该有的
 * 那种 N+1。所以会话行拿不到账号ID, 是架构的结果, 不是漏了。
 *
 * 前端已经有 `/api/companions` 这一份带账号ID的完整列表(通讯录在用), 所以把它做成索引
 * 是一次 O(n) 的本地 join。代价是一个 map, 换来的是零跨服务调用。
 *
 * <p>没有账号ID 的行**不进索引**(而不是映射成 null): 调用方 `handles.get(id)` 拿到
 * `undefined`, 与"这个人根本不在索引里"是同一种结果, 于是只有一条分支要处理。
 */
export function handleIndex(list: readonly HandleBearing[]): Map<string, string> {
  const index = new Map<string, string>()
  for (const item of list) {
    // 空串也要挡掉 —— 后端理论上不会给, 但 `handle: ''` 会在界面上画出一个空的灰块,
    // 那种"看起来像加载失败"的空白比不显示更难解释
    if (item.handle) index.set(item.id, item.handle)
  }
  return index
}

/**
 * 「还能改几次 / 下次能改是哪天」那句话。
 *
 * 两种状态**互斥**: 还有额度时说次数, 用完了说日期。后端保证 `nextChangeAt` 只在额度
 * 用尽时非空(见 `HandleQuota.of`), 这里不去猜哪一个是权威 —— 只按 `remaining` 分叉,
 * 日期缺失时退回一句不会错的话。
 *
 * <p>`nextChangeAt` 是 `LocalDateTime`(后端用 `toLocalDate()` 拼进 hint, 而字段本身是
 * 完整时间)。这里只取日期部分: 用户关心的是"哪一天能再改", 秒对他是噪音。
 */
export function quotaLabel(view: { remaining: number; limit: number; nextChangeAt?: string | null }): string {
  if (view.remaining > 0) {
    return `每年可修改 ${view.limit} 次，还剩 ${view.remaining} 次`
  }
  const day = view.nextChangeAt ? view.nextChangeAt.slice(0, 10) : ''
  return day ? `修改次数已用完，${day} 之后可以再改` : '修改次数已用完'
}

/**
 * 改号失败时给用户看的两行字。
 *
 * <h2>为什么是"读后端的 hint"而不是一张状态码映射表</h2>
 *
 * 后端的 400/409/429 各自带着一句写好的 `hint`(比如 409 带的是"可以试试 xiaoman_k3f"),
 * 而那些话里有**只有后端知道的信息** —— 撞上了哪一次、什么时候滑出窗口。
 * 前端再写一张表, 就是同一件事的第二份说法, 两份迟早不一致, 而不一致的表现是
 * 界面给的建议用不了。
 *
 * 所以这里只做一件事: 把 `status` 与 `hint` **取出来**, 不解释、不改写。
 * 状态码在这里的用途只有一个 —— 让界面能对 429 说一句"这次是真的改不动", 而不是
 * 把所有失败都画成红色错误。
 *
 * <p>非 {@link ApiError}(网络断了、fetch 自己抛的 TypeError)走 `message`, 那种情况下
 * 没有 hint 可用, 也不该编一个。
 */
export interface HandleFailure {
  message: string
  hint: string | null
  /** 一年三次用完了 —— 界面据此把"保存"按钮停掉, 而不是让用户反复撞同一面墙 */
  quotaExhausted: boolean
}

export function describeFailure(e: unknown): HandleFailure {
  if (e instanceof ApiError) {
    return {
      message: e.message,
      hint: e.hint,
      quotaExhausted: e.status === 429,
    }
  }
  return {
    message: e instanceof Error ? e.message : '修改失败',
    hint: null,
    quotaExhausted: false,
  }
}
