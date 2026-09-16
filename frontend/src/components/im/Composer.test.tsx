import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { Composer } from './Composer'

const noop = () => {}

describe('Composer', () => {
  it('placeholder 透传', () => {
    expect(
      renderToStaticMarkup(<Composer value="" onChange={noop} onSend={noop} placeholder="说点什么" />),
    ).toContain('说点什么')
  })

  it('没有内容时发送键禁用', () => {
    expect(renderToStaticMarkup(<Composer value="" onChange={noop} onSend={noop} />)).toContain('disabled')
  })

  it('只有空白也不可发 —— 空格不该被当成一条消息', () => {
    expect(renderToStaticMarkup(<Composer value="   " onChange={noop} onSend={noop} />)).toContain('disabled')
  })

  it('有内容时可发', () => {
    const out = renderToStaticMarkup(<Composer value="你好" onChange={noop} onSend={noop} />)
    // 输入框的 value 会渲染出来; 发送键此时不该带 disabled
    expect(out).toContain('value="你好"')
    expect(out).not.toContain('disabled=""')
  })

  it('disabled 时输入框禁用', () => {
    const out = renderToStaticMarkup(<Composer value="你好" onChange={noop} onSend={noop} disabled />)
    expect(out).toContain('disabled=""')
  })

  it('没有 onOpenPanel 时不画「+」', () => {
    const out = renderToStaticMarkup(<Composer value="" onChange={noop} onSend={noop} />)
    expect(out).not.toContain('更多')
  })

  it('有 onOpenPanel 时才画', () => {
    const out = renderToStaticMarkup(<Composer value="" onChange={noop} onSend={noop} onOpenPanel={noop} />)
    expect(out).toContain('更多')
  })

  it('额外动作按数组渲染', () => {
    const out = renderToStaticMarkup(
      <Composer
        value=""
        onChange={noop}
        onSend={noop}
        actions={[
          { key: 'a', icon: <span>★</span>, label: '收藏', onClick: noop },
          { key: 'b', icon: <span>☆</span>, label: '表情', onClick: noop },
        ]}
      />,
    )
    expect(out).toContain('收藏')
    expect(out).toContain('表情')
  })
})
