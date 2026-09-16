/**
 * 设计系统 —— 语义 token 层。
 *
 * **核心决策: 语义 CSS 变量 + `darkMode: 'class'`, 组件只用语义名, 全项目零 `dark:` 前缀。**
 *
 * 这样双主题的成本是每个概念**一个类名**, 而不是每个类名两遍。`bg-surface` 在亮暗下
 * 都对, 于是没有 `bg-white dark:bg-gray-900` 这种成对维护的负担 —— 那正是"简约"在
 * 代码侧的兑现。变量的定义在 `src/index.css` 的 `:root` / `.dark` 里。
 *
 * 旧的暖棕调色板(cocoa/ember/rosewood/jade)仍在, 但**只是过渡**: 现存 17 个文件里
 * 约 430 处裸色号还没改完。全部迁到语义名之后它们会被整体删除 —— 留着就等于留了
 * 一条能悄悄退回旧视觉的路。**新代码一律用语义名。**
 */
module.exports = {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // ── 面 ──────────────────────────────
        /** 页面底 */
        surface: 'rgb(var(--surface) / <alpha-value>)',
        /** 卡片/面板/浮层。亮色下与 surface 同色, 靠边框区分 —— 高级感来自边界, 不来自色块 */
        raised: 'rgb(var(--raised) / <alpha-value>)',
        /** 输入框/搜索框/代码块 —— 视觉上"凹进去"的地方 */
        sunken: 'rgb(var(--sunken) / <alpha-value>)',
        /** 遮罩 */
        scrim: 'rgb(var(--scrim) / <alpha-value>)',

        // ── 线 ──────────────────────────────
        /** 发丝分隔线 */
        line: 'rgb(var(--line) / <alpha-value>)',
        /** 需要被看见的边框(聚焦、选中) */
        'line-strong': 'rgb(var(--line-strong) / <alpha-value>)',

        // ── 字 ──────────────────────────────
        ink: 'rgb(var(--ink) / <alpha-value>)',
        'ink-soft': 'rgb(var(--ink-soft) / <alpha-value>)',
        'ink-faint': 'rgb(var(--ink-faint) / <alpha-value>)',

        // ── 强调 ────────────────────────────
        /** 电光蓝。全局唯一的强调色 —— 按钮、选中态、自己发出的气泡都是它 */
        accent: 'rgb(var(--accent) / <alpha-value>)',
        /** 淡蓝底(选中行、标签) */
        'accent-soft': 'rgb(var(--accent-soft) / <alpha-value>)',
        /** 蓝底上的字 */
        'accent-ink': 'rgb(var(--accent-ink) / <alpha-value>)',

        ok: 'rgb(var(--ok) / <alpha-value>)',
        warn: 'rgb(var(--warn) / <alpha-value>)',
        danger: 'rgb(var(--danger) / <alpha-value>)',

        // ── IM 气泡 ─────────────────────────
        'bubble-in': 'rgb(var(--bubble-in) / <alpha-value>)',
        'bubble-out': 'rgb(var(--bubble-out) / <alpha-value>)',
        'bubble-out-ink': 'rgb(var(--bubble-out-ink) / <alpha-value>)',

        // ── 过渡期遗留(第 8 步全部迁走后删除) ──────
        cocoa: {
          950: '#14100e', 900: '#1a1512', 850: '#201a16', 800: '#272019', 700: '#332a21',
          600: '#473a2d', 500: '#6b5844', 400: '#9a8168', 300: '#c3ac90', 200: '#dfd0ba',
          100: '#efe5d6', 50: '#f9f4ec',
        },
        ember: { DEFAULT: '#d97757', soft: '#e8b467', deep: '#b45a3f', pale: '#f4d7c4' },
        rosewood: { DEFAULT: '#a85d6f', soft: '#c98a97' },
        jade: { DEFAULT: '#5f9e7d', soft: '#8ec2a6' },
      },
      fontFamily: {
        sans: ['Inter', 'PingFang SC', 'HarmonyOS Sans SC', 'Microsoft YaHei', '-apple-system', 'sans-serif'],
        /** 技术标识专用: API key / session id / agent id。科技感来自"确定性", 等宽是它最省的载体 */
        mono: ['"JetBrains Mono"', '"SF Mono"', 'Menlo', 'Consolas', 'monospace'],
      },
      boxShadow: {
        /** 全局唯一的阴影。只给浮层用 —— 页面上的卡片一律靠边框, 不靠投影 */
        pop: 'var(--shadow-pop)',
      },
      transitionDuration: { DEFAULT: '150ms' },
      keyframes: {
        fadeUp: {
          '0%': { opacity: '0', transform: 'translateY(8px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        fadeIn: { '0%': { opacity: '0' }, '100%': { opacity: '1' } },
        pulseSoft: {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '0.45' },
        },
      },
      animation: {
        fadeUp: 'fadeUp .35s ease-out both',
        fadeIn: 'fadeIn .25s ease-out both',
        pulseSoft: 'pulseSoft 1.6s ease-in-out infinite',
      },
    },
  },
  plugins: [],
}
