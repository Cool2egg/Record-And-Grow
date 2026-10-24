import { useRef, useState } from 'react'
import dayjs, { type Dayjs } from 'dayjs'
import { timelineApi } from '../../api'
import type { TimelineEntry } from '../../api/types'
import { TimeInput } from './TimeInput'

function combine(date: Dayjs, hour: string, minute: string): Dayjs {
  const safeHour = Math.min(23, Math.max(0, Number(hour || 0)))
  const safeMinute = Math.min(59, Math.max(0, Number(minute || 0)))
  return date.hour(safeHour).minute(safeMinute).second(0).millisecond(0)
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

function DateChips({
  value,
  onChange,
}: {
  value: Dayjs
  onChange: (date: Dayjs) => void
}) {
  const [showCustom, setShowCustom] = useState(false)
  const today = dayjs().startOf('day')
  const options = [
    { label: '今天', date: today },
    { label: '昨天', date: today.subtract(1, 'day') },
    { label: '前天', date: today.subtract(2, 'day') },
  ]
  const quickMatch = options.some((option) => option.date.isSame(value, 'day'))
  const custom = showCustom || !quickMatch

  return (
    <div className="date-chips">
      {options.map((option) => (
        <button
          type="button"
          key={option.label}
          className={`date-chip ${option.date.isSame(value, 'day') ? 'active' : ''}`}
          onClick={() => {
            setShowCustom(false)
            onChange(option.date)
          }}
        >
          {option.label}
        </button>
      ))}
      <button
        type="button"
        className={`date-chip ${custom ? 'active' : ''}`}
        onClick={() => setShowCustom(true)}
      >
        其他…
      </button>
      {custom ? (
        <input
          type="date"
          className="date-native"
          value={value.format('YYYY-MM-DD')}
          onChange={(event) => {
            if (!event.target.value) return
            onChange(dayjs(event.target.value))
          }}
        />
      ) : null}
    </div>
  )
}

export function EntryForm({
  initial,
  defaultDate,
  onCancel,
  onSaved,
}: {
  initial?: TimelineEntry
  defaultDate: Dayjs
  onCancel: () => void
  onSaved: (entry: TimelineEntry) => void
}) {
  const now = dayjs()
  const initialStart = initial
    ? dayjs(initial.startTime)
    : defaultDate.hour(now.hour()).minute(now.minute()).second(0).millisecond(0)
  const initialEnd = initial?.endTime ? dayjs(initial.endTime) : initialStart

  const [content, setContent] = useState(initial?.content ?? '')
  const [startDate, setStartDate] = useState(initialStart.startOf('day'))
  const [startHour, setStartHour] = useState(pad(initialStart.hour()))
  const [startMinute, setStartMinute] = useState(pad(initialStart.minute()))
  const [endDate, setEndDate] = useState(initialEnd.startOf('day'))
  const [endHour, setEndHour] = useState(pad(initialEnd.hour()))
  const [endMinute, setEndMinute] = useState(pad(initialEnd.minute()))
  const [ongoing, setOngoing] = useState(initial ? initial.endTime === null : false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const startMinuteRef = useRef<HTMLInputElement | null>(null)
  const endMinuteRef = useRef<HTMLInputElement | null>(null)

  const start = combine(startDate, startHour, startMinute)
  const end = combine(endDate, endHour, endMinute)
  const invalidRange = !ongoing && end.isBefore(start)

  const applyStart = (patch: { date?: Dayjs; hour?: string; minute?: string }) => {
    const oldStart = combine(startDate, startHour, startMinute)
    const follows = !ongoing && combine(endDate, endHour, endMinute).isSame(oldStart)
    const nextDate = patch.date ?? startDate
    const nextHour = patch.hour ?? startHour
    const nextMinute = patch.minute ?? startMinute
    setStartDate(nextDate)
    setStartHour(nextHour)
    setStartMinute(nextMinute)
    if (follows) {
      setEndDate(nextDate)
      setEndHour(nextHour)
      setEndMinute(nextMinute)
    }
  }

  const applyEnd = (patch: { date?: Dayjs; hour?: string; minute?: string }) => {
    setEndDate(patch.date ?? endDate)
    setEndHour(patch.hour ?? endHour)
    setEndMinute(patch.minute ?? endMinute)
  }

  const submit = async () => {
    const text = content.trim()
    if (!text) {
      setError('先写点什么，再保存。')
      return
    }
    if (invalidRange) {
      setError('结束时间不能早于开始时间。')
      return
    }
    setSaving(true)
    setError(null)
    try {
      const body = {
        content: text,
        startTime: start.format('YYYY-MM-DDTHH:mm:ssZ'),
        endTime: ongoing ? null : end.format('YYYY-MM-DDTHH:mm:ssZ'),
      }
      const saved = initial
        ? await timelineApi.update(initial.id, body)
        : await timelineApi.create(body)
      onSaved(saved)
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : '保存失败，稍后再试。')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div
      className="entry-form"
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.preventDefault()
          onCancel()
        }
      }}
    >
      <textarea
        value={content}
        onChange={(event) => setContent(event.target.value)}
        placeholder="这段时间做了什么？"
        autoFocus
        aria-label="记录内容"
      />

      <div className="time-row">
        <span className="time-row-label">开始</span>
        <DateChips value={startDate} onChange={(date) => applyStart({ date })} />
        <TimeInput
          value={startHour}
          onChange={(hour) => applyStart({ hour })}
          max={23}
          label="开始小时"
          onComplete={() => startMinuteRef.current?.focus()}
        />
        <span className="time-unit">时</span>
        <TimeInput
          value={startMinute}
          onChange={(minute) => applyStart({ minute })}
          max={59}
          label="开始分钟"
          inputRef={startMinuteRef}
        />
        <span className="time-unit">分</span>
      </div>

      <div className="time-row">
        <span className="time-row-label">结束</span>
        <DateChips value={endDate} onChange={(date) => applyEnd({ date })} />
        <TimeInput
          value={endHour}
          onChange={(hour) => applyEnd({ hour })}
          max={23}
          label="结束小时"
          onComplete={() => endMinuteRef.current?.focus()}
          disabled={ongoing}
        />
        <span className="time-unit">时</span>
        <TimeInput
          value={endMinute}
          onChange={(minute) => applyEnd({ minute })}
          max={59}
          label="结束分钟"
          inputRef={endMinuteRef}
          disabled={ongoing}
        />
        <span className="time-unit">分</span>
        <label className="checkbox-line">
          <input
            type="checkbox"
            checked={ongoing}
            onChange={(event) => {
              const checked = event.target.checked
              setOngoing(checked)
              if (!checked) {
                setEndDate(startDate)
                setEndHour(startHour)
                setEndMinute(startMinute)
              }
            }}
          />
          进行中
        </label>
      </div>

      {ongoing ? <div className="form-hint">进行中的记录会一直留在时间线上，直到你补上结束时间。</div> : null}
      {invalidRange ? <div className="form-error">结束时间不能早于开始时间。</div> : null}
      {error ? <div className="form-error">{error}</div> : null}

      <div className="form-actions">
        <button
          type="button"
          className="ghost-btn primary"
          onClick={() => void submit()}
          disabled={saving}
        >
          {saving ? '保存中…' : initial ? '保存修改' : '记下'}
        </button>
        <button type="button" className="text-action" onClick={onCancel}>
          取消
        </button>
        <span className="form-hint">Esc 也可以取消</span>
      </div>
    </div>
  )
}
