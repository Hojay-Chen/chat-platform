/**
 * 从 `Chat.tsx:914-916` 原样搬出来的。
 *
 * 单独占一个文件而不是塞进调用方, 是因为截断会出现在会话列表摘要、
 * 通知预览、应用卡片三处 —— 三处各写一遍 `slice + '…'` 就会出现
 * 有的地方补了省略号有的没补。省略号本身也有意义: 它告诉用户
 * "这里被截断了", 而不是"消息就这么短"。
 */
export function truncate(s: string, n: number): string {
  return s.length > n ? s.slice(0, n) + '…' : s
}
