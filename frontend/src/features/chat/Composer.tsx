import { ArrowUpOutlined, StopOutlined } from '@ant-design/icons'
import { useEffect, type KeyboardEvent, type RefObject } from 'react'

export function Composer({
  value,
  onChange,
  onSend,
  onStop,
  streaming,
  disabled,
  textareaRef,
}: {
  value: string
  onChange: (value: string) => void
  onSend: () => void
  onStop: () => void
  streaming: boolean
  disabled?: boolean
  textareaRef: RefObject<HTMLTextAreaElement | null>
}) {
  useEffect(() => {
    const node = textareaRef.current
    if (!node) return
    node.style.height = 'auto'
    node.style.height = `${Math.min(node.scrollHeight, 168)}px`
  }, [value, textareaRef])

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.nativeEvent.isComposing) return
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      if (!streaming) onSend()
    }
  }

  const canSend = value.trim().length > 0 && !disabled

  return (
    <div className="composer-inner">
      <div className="composer">
        <textarea
          ref={(node) => {
            textareaRef.current = node
          }}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={onKeyDown}
          placeholder="记一笔，或者问点什么"
          rows={1}
          aria-label="输入问题或记录"
        />
        <div className="composer-actions">
          {streaming ? (
            <button type="button" className="icon-btn stop" onClick={onStop} aria-label="停止生成">
              <StopOutlined />
            </button>
          ) : (
            <button
              type="button"
              className="icon-btn"
              onClick={onSend}
              disabled={!canSend}
              aria-label="发送"
            >
              <ArrowUpOutlined />
            </button>
          )}
        </div>
      </div>
      <div className="composer-hint">
        <span>
          <kbd>Enter</kbd> 发送 · <kbd>Shift</kbd> + <kbd>Enter</kbd> 换行
        </span>
        <span>{streaming ? '生成中，可随时停' : '回答只基于你的记录'}</span>
      </div>
    </div>
  )
}
