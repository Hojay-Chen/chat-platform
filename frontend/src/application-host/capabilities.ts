import type { SurfaceType } from '@/api/lap'
import type { HostCapability } from './protocol'

/**
 * 「这个应用此刻被允许做什么」—— §17 的 `permissions` 与 §24 那条「无权限不能进入」的落点。
 *
 * <h2>权限从哪来: 容器, 而不是清单</h2>
 *
 * 最容易想到的做法是让 manifest 自己申报权限(`"permissions": ["SHARE"]`), 然后在应用详情
 * 页上写一句"这个应用想获得分享能力"。这里**没有**那么做, 理由是两条都指向同一个结论:
 *
 * <ol>
 *   <li><b>权限的授予者是用户, 不是应用。</b> 一个应用能申报的东西只有"我想要什么", 而
 *       "我给不给"必须由宿主根据**当下这一场**决定。把它做成 manifest 字段, 等于让应用
 *       替用户回答那个问题。</li>
 *   <li><b>宿主真正知道、且真的会变的那个事实是「应用此刻占着多大的地方」。</b> §6 的五种
 *       Surface 本来就是宿主与应用之间的交互契约, 而"我能不能在你上面再浮一个聊天窗"
 *       恰恰是一个关于**地方**的问题。</li>
 * </ol>
 *
 * 所以判据是 {@link surfaceGrants}: 整页 / 弹窗 / 抽屉里的应用可以要聊天浮窗与分享;
 * 嵌在别人页面里的一块(EMBEDDED)、以及一行(INLINE)什么都不给 —— 那两种情况下应用连自己的
 * 边界都没拥有, 在它上面浮一个全平台的聊天窗是宿主在越自己的权。
 *
 * <h2>为什么这个函数是纯的并且单独一个文件</h2>
 *
 * 「谁被允许做什么」是这一层里唯一一处**错了不会报错**的东西: 判宽了, 一个嵌在别人页面里的
 * 第三方应用会拿到一整块聊天浮窗; 判窄了, 用户在游戏里点"分享"会得到一句"没有权限", 而没人
 * 知道为什么。两种都不会抛异常。所以它必须能被断言 —— 而本仓前端测试跑在 node 上, 没有
 * jsdom, 不能渲染组件去间接测它。
 */

/** §17 的 `permissions` 词表。**刻意只有两个** —— 见 {@link surfaceGrants} 的第三段。 */
export const HOST_PERMISSIONS = ['SHARE', 'CHAT_OVERLAY'] as const

export type HostPermission = (typeof HOST_PERMISSIONS)[number]

/**
 * 每种容器授予哪些宿主能力。
 *
 * <h2>为什么 EMBEDDED 与 INLINE 是空的</h2>
 *
 * 它们不是"小一点的整页", 而是**别人页面里的一块**。§6 的表格里 EMBEDDED 那一行的 Host 行为
 * 是"Host 管理生命周期" —— 那个 Host 是**那一页的主人**, 不是聊天平台。在这两种容器里给应用
 * 聊天浮窗, 等于让它在不属于它的页面上开一个全平台级的窗口。
 *
 * <h2>为什么 MODAL / PANEL 有 CHAT_OVERLAY</h2>
 *
 * 它们各自盖住了当前页的一整块(弹窗铺满遮罩、抽屉占满一侧)。用户在那种容器里"想边玩边聊"
 * 是合理的, 而浮窗浮在弹窗之上不会让任何东西失去意义 —— 底下那层本来就是被盖住的。
 */
const GRANTS: Record<SurfaceType, readonly HostPermission[]> = {
  FULL_PAGE: ['SHARE', 'CHAT_OVERLAY'],
  MODAL: ['SHARE', 'CHAT_OVERLAY'],
  PANEL: ['SHARE', 'CHAT_OVERLAY'],
  EMBEDDED: [],
  INLINE: [],
}

/** 这个容器里应用被授予的权限。没声明过的容器(未来的第六种)保守地什么都不给。 */
export function surfaceGrants(surface: SurfaceType): HostPermission[] {
  return [...(GRANTS[surface] ?? [])]
}

/**
 * 每个宿主能力需要哪一项权限。**不需要权限的那些不在这张表里** —— 它们是"任何容器里都成立"
 * 的三个: 说自己画好了、说自己要关了、问自己是谁。
 *
 * <p>`session.context` 也不在表里, 这是刻意的: 应用要**先能问出自己有什么权限**, 才谈得上
 * 权限检查。把上下文查询本身设成需要权限, 会让应用永远拿不到那个答案。
 */
const REQUIRED: Partial<Record<HostCapability, HostPermission>> = {
  'share.request': 'SHARE',
  'chat.openWindow': 'CHAT_OVERLAY',
  'chat.minimize': 'CHAT_OVERLAY',
  'chat.openConversation': 'CHAT_OVERLAY',
}

/**
 * 这个能力此刻能不能用。
 *
 * 返回的是**判据**, 不是错误消息 —— 消息由调用方按场合写(宿主日志要一种措辞, 回给应用的错误
 * 信封要另一种)。判据与措辞分开, 是为了让措辞能改而不动这条规则。
 */
export function isAllowed(capability: HostCapability, granted: readonly HostPermission[]): boolean {
  const needed = REQUIRED[capability]
  if (!needed) return true
  return granted.includes(needed)
}

/** 缺哪一项权限 —— 拒绝时回给应用的那个码就是它。`null` = 不缺。 */
export function missingPermission(
  capability: HostCapability,
  granted: readonly HostPermission[],
): HostPermission | null {
  const needed = REQUIRED[capability]
  if (!needed) return null
  return granted.includes(needed) ? null : needed
}

/** 用户身份的公开面 (§15 `ApplicationUser`)。**只有这三个字段**, 见那个接口下面的禁令清单。 */
export interface ApplicationUser {
  id: string
  displayName: string
  avatarUrl?: string
}

/** 应用能看到的"我在哪"。§17 那个视图里属于宿主的四个字段。 */
export interface SessionContext {
  applicationId: string
  sessionId: string
  surface: SurfaceType
  /** 这一场挂在哪段对话上。没有(从分享链接直接进来的)时为空 —— 不是错误。 */
  conversationId?: string
  permissions: HostPermission[]
}
