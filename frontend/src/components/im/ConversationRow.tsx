import { Avatar } from './Avatar'
import { ListRow } from './ListRow'
import { NameWithHandle } from './NameWithHandle'
import { UnreadBadge } from './UnreadBadge'
import type { ConversationSummary } from '@/api/conversations'
import { previewText } from '@/lib/conversations'
import { relativeListTime } from '@/lib/time'

/**
 * 会话列表里的一行。
 *
 * 做成一个**纯展示件**而不是把这段 JSX 写在 `ChatList` 里, 是为了它能被
 * `renderToStaticMarkup` 直接断言: `ChatList` 要 fetch、要轮询、要 navigate,
 * 在 `environment: 'node'` 下根本渲染不起来 —— 把行放进页面里就等于这一行
 * 永远不会被任何测试看见。
 *
 * 一行里没有一个字段是从 `senderType` 推断出来的: 左右、头像、昵称都来自显式传入的
 * `summary`。二期接入真人会话与群聊时, 这个组件收的还是 `ConversationSummary`,
 * 只是 `peer.kind` 变成 `'user'`/`'group'` —— 而头像的角标已经认这个 kind 了。
 */
export function ConversationRow({ summary, handle, onOpen }: {
  summary: ConversationSummary
  /**
   * 对方的账号ID。由 `ChatList` 从通讯录那份数据 join 出来 —— 这个组件**不去取**它,
   * 因为一个纯展示件一旦开始 fetch 就不再能被 `renderToStaticMarkup` 断言了。
   */
  handle?: string
  onOpen?: () => void
}) {
  const name = summary.peer.name || summary.title
  const preview = previewText(summary.lastMessage)

  return (
    <ListRow
      leading={<Avatar name={name} kind="agent" />}
      // 账号ID 挂在名字**右边**, 不占副标题 —— 副标题是最后一条消息, 那是"最近发生了什么"。
      // 把账号ID 放那里会让聊天列表失去它唯一的信息(见 Contacts 里那段"名字下面不写任何东西")
      title={handle ? <NameWithHandle name={name} handle={handle} /> : name}
      // 没说过话的会话不画副标题 —— 画一个空 span 会把行高撑出半行空白
      subtitle={preview || undefined}
      trailingText={summary.lastMessageAt ? relativeListTime(summary.lastMessageAt) : undefined}
      badge={<UnreadBadge count={summary.unreadCount} muted={summary.muted} />}
      muted={summary.muted}
      // 置顶行给一层极淡的底 —— 微信也是这样, 不加分组标题。加了标题会让列表多出一行
      // "置顶"分隔条, 而置顶通常只有一两行, 那个标题比内容还高
      className={summary.pinned ? 'bg-sunken/70' : ''}
      onClick={onOpen}
    />
  )
}
