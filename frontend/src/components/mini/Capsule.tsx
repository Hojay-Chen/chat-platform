import { MoreHorizontal, X } from 'lucide-react'

/**
 * 悬浮胶囊 —— 小程序跑起来后, 平台上**唯一**还留在屏幕上的东西。
 *
 * <h2>它取代的是什么</h2>
 *
 * 之前从「发现」打开一个应用, 看到的是一条聊天平台的头部(返回箭头 + 应用名 +
 * 会话 id + 离开/结束)、一条"以…打开 FULL_PAGE EMBEDDED MODAL PANEL INLINE"的
 * 工具栏、然后是应用、然后是参与者名单与邀请区。用户的原话是
 * 「为何还是在聊天平台通过聊天平台的栏目来进行操作」—— 那条头部和工具栏就是
 * 那套"栏目"。
 *
 * 微信的小程序不是这样: 应用铺满整屏, 平台只剩右上角一枚胶囊(··· 与 ⊙)。
 * 这一枚就是本组件。
 *
 * <h2>为什么是"悬浮"而不是一条标题栏</h2>
 *
 * 一条标题栏会占掉一条通栏的高度, 并且把下面那块地方变成"内容区"—— 应用又变回了
 * 网页里的一块。胶囊浮在应用之上, 应用因此拥有**整个**视口, 包括胶囊底下那一片。
 * 代价是应用右上角可能有东西被压住, 微信也有同样的代价, 它选择让应用自己避让。
 *
 * <h2>这里刻意没有的东西</h2>
 *
 * 没有应用名, 没有会话 id, 没有平台名。胶囊不是用来介绍自己的 —— 应用正在屏幕上,
 * 用户知道自己在哪。会话 id / 参与者 / 邀请链接这些"关于这一场的信息"全部收进
 * `···`, 也就是 {@link ActionSheet}。那条路径与微信一致: 想知道细节的人点 ···,
 * 不想知道的人永远不会被它打扰。
 */
export default function Capsule({
  onMore,
  onLeave,
  busy = false,
}: {
  /** 打开 `···` 面板 */
  onMore: () => void
  /** 离开 —— 相当于微信小程序胶囊右侧那个 ⊙ */
  onLeave: () => void
  busy?: boolean
}) {
  /*
   * `focus-visible:ring-inset` 而不是普通 ring: 胶囊是 `overflow-hidden rounded-full`,
   * 画在外面的那一圈会被裁掉 —— 而那正是"键盘用户什么都看不见"的老问题。
   */
  const btn =
    'grid h-8 w-11 place-items-center text-ink-soft transition-colors hover:bg-sunken hover:text-ink focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-accent/50 disabled:opacity-40'

  return (
    <div
      data-testid="mini-capsule"
      /*
       * 这个组件**不带定位** —— 钉在哪儿由宿主决定, 见 `AppSession`。
       *
       * 它曾经是 `fixed right-3 top-3`: 那是按"应用铺满整个视口"写的。桌面端(≥640px)
       * 小程序改成一条居中的窄列之后, `fixed` 会把胶囊钉到**视口**右上角 —— 离应用的
       * 右上角可能有一整个屏幕那么远, 看起来像飘在页面外面。
       *
       * `z-40` 留着: 浮层与面板是 z-50, 胶囊必须被它们盖住。
       */
      className="z-40 flex items-center overflow-hidden rounded-full border border-line bg-surface/75 shadow-pop backdrop-blur-md"
    >
      <button
        type="button"
        onClick={onMore}
        disabled={busy}
        title="更多"
        aria-label="更多"
        className={btn}
      >
        <MoreHorizontal size={17} />
      </button>
      {/* 分隔线: 微信那枚胶囊中间也有一条, 它让"两个动作"这件事看得见 */}
      <span aria-hidden className="h-4 w-px bg-line" />
      <button
        type="button"
        onClick={onLeave}
        disabled={busy}
        title="离开小程序"
        aria-label="离开小程序"
        className={btn}
      >
        <X size={15} />
      </button>
    </div>
  )
}
