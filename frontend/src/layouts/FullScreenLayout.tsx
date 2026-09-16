import { Outlet } from 'react-router-dom'

/**
 * 布局 B: 全屏, 没有 tab bar。
 *
 * 聊天室、联系人资料、应用详情走的都是这一支。它们共同的特征是**自己管自己的纵向空间**:
 * 聊天室要把输入框钉在底部、资料页要吸顶返回栏。留一个 tab bar 在下面会把这块空间切碎。
 *
 * 这里用 `h-dvh` + `overflow-hidden` 而不是 `min-h-screen`:
 * - `dvh` 而非 `vh` —— 移动端浏览器地址栏收起/展开时 `100vh` 会跳一下
 * - `overflow-hidden` 让**页面自己**决定哪一段滚动。子页面把可滚动区标成
 *   `flex-1 overflow-y-auto`, 吸顶/钉底的部分就天然不动了。
 *   如果这里用整体滚动, 聊天室的输入框会跟着消息一起被滚出屏幕。
 */
export default function FullScreenLayout() {
  return (
    <div className="flex h-dvh flex-col overflow-hidden bg-surface">
      <Outlet />
    </div>
  )
}
