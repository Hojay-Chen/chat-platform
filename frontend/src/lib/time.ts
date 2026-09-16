import { format } from 'date-fns'

/**
 * 时间显示 —— 从 `Chat.tsx:918-979` 抽出来的。
 *
 * 抽出来有两个理由，第二个才是重点：
 * 1. 会话列表、通讯录、消息流三处都要用同一套规则, 复制三份必然漂移。
 * 2. 这些函数原来躺在 Chat.tsx 里, 而 Chat.tsx 是个 1397 行、需要 fetch 与 SSE
 *    才能渲染的组件 —— 意味着 `dateLabel()` 那条"今天/昨天/更早"的三分支
 *    从来没有被任何测试碰过。它们与 React 无关, 不该和组件绑在一起。
 *
 * 所有函数都接受可选的 `now` —— 不传就是真实当下。可注入是为了让"昨天"这种
 * 相对判断能被确定性地断言, 而不是靠测试运行时恰好是几点。
 */

export function isSameDay(a: Date, b: Date): boolean {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  )
}

/** 消息流里的日期分隔条: 今天 / 昨天 / 2026年9月1日 */
export function dateLabel(iso: string, now: Date = new Date()): string {
  const d = new Date(iso)
  if (isSameDay(d, now)) return '今天'
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (isSameDay(d, yesterday)) return '昨天'
  return format(d, 'yyyy年M月d日')
}

/** 消息气泡上的时刻 */
export function formatTime(iso: string): string {
  return format(new Date(iso), 'HH:mm')
}

const WEEKDAY_ZH = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']

/**
 * 会话列表右上角那一个时间 —— 微信的规则, 比消息流里的日期分隔更紧凑。
 *
 * 它和 `dateLabel` 的区别是刻意的: 会话列表一行只有 320px, "2026年9月1日" 放不下,
 * 而"星期三"比"9月10日"更容易让人想起"哦那是前天聊的"。
 */
export function relativeListTime(iso: string, now: Date = new Date()): string {
  const d = new Date(iso)
  if (isSameDay(d, now)) return format(d, 'HH:mm')
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (isSameDay(d, yesterday)) return '昨天'
  // 一周内用星期几 —— 用日期差而不是 ISO 周数, 否则"上周五"和"这周一"会算成同一周
  const days = Math.floor((startOfDay(now).getTime() - startOfDay(d).getTime()) / 86400000)
  if (days < 7) return WEEKDAY_ZH[d.getDay()]
  return format(d, 'yyyy/M/d')
}

/** 把时刻抹平到当天零点 —— 算"差几天"必须用它, 否则 23:59 到 00:01 会被算成 0 天 */
function startOfDay(d: Date): Date {
  const c = new Date(d)
  c.setHours(0, 0, 0, 0)
  return c
}

/** 她最近 / 通知列表里的相对时刻: 刚刚 / 12 分钟前 / 3 小时前 / 5 天前 / 2026年8月1日 */
export function timeAgo(iso: string, now: Date = new Date()): string {
  const diffMs = now.getTime() - new Date(iso).getTime()
  const mins = Math.floor(diffMs / 60000)
  if (mins < 1) return '刚刚'
  if (mins < 60) return `${mins} 分钟前`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days} 天前`
  return format(new Date(iso), 'yyyy年M月d日')
}
