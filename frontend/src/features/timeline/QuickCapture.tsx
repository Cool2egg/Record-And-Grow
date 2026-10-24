import { useState } from 'react'
import dayjs from 'dayjs'
import { timelineApi } from '../../api'
import type { TimelineEntry } from '../../api/types'

export function QuickCapture({ onSaved }: { onSaved: (entry: TimelineEntry) => void }) {
  const [content, setContent] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [justSaved, setJustSaved] = useState(false)

  const save = async () => {
    const text = content.trim()
    if (!text || saving) return
    setSaving(true)
    setError(null)
    const now = dayjs()
    const iso = now.format('YYYY-MM-DDTHH:mm:ssZ')
    try {
      const entry = await timelineApi.create({
        content: text,
        startTime: iso,
        endTime: iso,
      })
      setContent('')
      setJustSaved(true)
      window.setTimeout(() => setJustSaved(false), 1600)
      onSaved(entry)
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : '没记上，稍后再试。')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div>
      <div className="quick-capture">
        <input
          value={content}
          onChange={(event) => setContent(event.target.value)}
          onKeyDown={(event) => {
            if (event.nativeEvent.isComposing) return
            if (event.key === 'Enter') {
              event.preventDefault()
              void save()
            }
          }}
          placeholder="记一笔，现在发生的事"
          aria-label="快速记录"
        />
        <span className="now">{justSaved ? '已记下' : dayjs().format('HH:mm')}</span>
        <button
          type="button"
          className="ghost-btn primary"
          onClick={() => void save()}
          disabled={!content.trim() || saving}
        >
          {saving ? '…' : '记下'}
        </button>
      </div>
      {error ? <div className="form-error" style={{ marginTop: 6 }}>{error}</div> : null}
    </div>
  )
}
