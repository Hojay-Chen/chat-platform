import { useRoutes } from 'react-router-dom'
import { routes } from './routes'

// 内置应用的登记 —— 必须在任何页面渲染之前跑完。放在 App 里是刻意的:
// 它是"这份界面认得哪些应用"这件事的入口, 而不是某个页面的局部依赖。
import '@/apps'

/**
 * `App` 只做一件事: 把路由表渲染出来。
 *
 * 表本身在 `routes.tsx` —— 那里能被测试直接断言, 这里不能。
 * 之前这一百来行 JSX 是写在这个文件里的, 于是"路由对不对"这件事
 * 既没有编译期保障, 也没有任何测试能碰到。
 */
export default function App() {
  return useRoutes(routes)
}
