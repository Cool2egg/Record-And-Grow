import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { App as AntdApp, DatePicker } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useSearchParams } from 'react-router-dom'
import { timelineApi } from '../../api'
import type { TimelineEntry } from '../../api/types'
import { ErrorBlock } from '../../components/PageChrome'
import { formatRange, weekday } from '../../lib/format'
import { QuickCapture } from './QuickCapture'
import { EntryForm } from './EntryForm'
import { DayRuler } from './DayRuler'
import { TimeInput } from './TimeInput'

function EndTimeEditor({
  entry,
  onCancel,
  onSaved,
}: {
  entry: TimelineEntry
  onCancel: () => void
  onSaved: () => void
}) {
  const now = dayjs()
  const [hour, setHour] = useState(String(now.hour()).padStart(2, '0'))
  const [minute, setMinute] = useState(String(now.minute()).padStart(2, '0'))
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const save = async () => {
    const hourValue = Math.min(23, Math.max(0, Number(hour || 0)))
    const minuteValue = Math.min(59, Math.max(0, Number(minute || 0)))
    const end = now
      .hour(hourValue)
      .minute(minuteValue)
      .second(0)
      .millisecond(0)
    if (end.isBefore(dayjs(entry.startTime))) {
      setError('结束时间不能早于开始时间。')
      return
    }
    setSaving(true)
    setError(null)
    try {
      await timelineApi.update(entry.id, {
        content: entry.content,
        startTime: dayjs(entry.startTime).format('YYYY-MM-DDTHH:mm:ssZ'),
        endTime: end.format('YYYY-MM-DDTHH:mm:ssZ'),
      })
      onSaved()
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : '没保存上。')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="end-editor">
      <span className="time-row-label">结束于</span>
      <TimeInput
        value={hour}
        onChange={setHour}
        max={23}
        label="结束小时"
      />
      <span className="time-unit">时</span>
      <TimeInput
        value={minute}
        onChange={setMinute}
        max={59}
        label="结束分钟"
      />
      <span className="time-unit">分</span>
      <span className="form-hint" style={{ margin: 0 }}>
        {now.format('M月D日')}
      </span>
      <button type="button" className="ghost-btn primary" onClick={() => void save()} disabled={saving}>
        {saving ? '保存中…' : '填好'}
      </button>
      <button type="button" className="text-action" onClick={onCancel}>
        取消
      </button>
      {error ? <span className="form-error">{error}</span> : null}
    </div>
  )
}

export function TimelinePage() {
  const { message } = AntdApp.useApp()
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()

  const parsed = searchParams.get('date') ? dayjs(searchParams.get('date')) : dayjs()
  const selectedDate = parsed.isValid() ? parsed.startOf('day') : dayjs().startOf('day')
  const focusId = Number(searchParams.get('focus')) || null

  const [formOpen, setFormOpen] = useState(false)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [endingId, setEndingId] = useState<number | null>(null)
  const [deletingId, setDeletingId] = useState<number | null>(null)
  const [highlight, setHighlight] = useState<number | null>(null)

  const startIso = selectedDate.format('YYYY-MM-DDTHH:mm:ssZ')
  const endIso = selectedDate.add(1, 'day').format('YYYY-MM-DDTHH:mm:ssZ')

  const entriesQuery = useQuery({
    queryKey: ['timeline', selectedDate.format('YYYY-MM-DD')],
    queryFn: () => timelineApi.list(startIso, endIso, 100),
    retry: false,
  })

  const entries = entriesQuery.data?.items ?? []
  const dayEntries = entries.filter((entry) =>
    dayjs(entry.startTime).isSame(selectedDate, 'day'),
  )
  const carryover = entries.filter(
    (entry) => !dayjs(entry.startTime).isSame(selectedDate, 'day'),
  )
  const ongoingCarryover = carryover.filter((entry) => entry.endTime === null)
  const endedCarryover = carryover.filter((entry) => entry.endTime !== null)
  // 更早开始、仍未结束的记录会覆盖整天，不画进 24 小时尺，避免尺子变成一条实线
  const rulerEntries = [...dayEntries, ...endedCarryover]

  const refresh = () => {
    void queryClient.invalidateQueries({
      queryKey: ['timeline', selectedDate.format('YYYY-MM-DD')],
    })
  }

  const goToDate = (date: Dayjs) => {
    const next = new URLSearchParams(searchParams)
    next.set('date', date.format('YYYY-MM-DD'))
    next.delete('focus')
    setSearchParams(next)
    setEditingId(null)
    setEndingId(null)
    setDeletingId(null)
    setFormOpen(false)
  }

  const focusEntry = (id: number) => {
    const next = new URLSearchParams(searchParams)
    next.set('focus', String(id))
    setSearchParams(next)
  }

  useEffect(() => {
    if (!focusId) {
      setHighlight(null)
      return
    }
    const node = document.querySelector(`[data-entry-id="${focusId}"]`)
    if (!node) return
    // 被引用的是「跨日进入 / 更早开始」里的条目时，先展开它所在的折叠区
    const group = node.closest('details')
    if (group) group.open = true
    requestAnimationFrame(() => node.scrollIntoView({ behavior: 'smooth', block: 'center' }))
    setHighlight(focusId)
    const timer = window.setTimeout(() => setHighlight(null), 2200)
    return () => window.clearTimeout(timer)
  }, [focusId, entries])

  const remove = async (entry: TimelineEntry) => {
    try {
      await timelineApi.remove(entry.id)
      setDeletingId(null)
      message.success('已删除，记录本上留了一条浅印。')
      refresh()
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '删除失败')
    }
  }

  const renderRow = (entry: TimelineEntry) => {
    if (editingId === entry.id) {
      return (
        <EntryForm
          key={`edit-${entry.id}`}
          initial={entry}
          defaultDate={selectedDate}
          onCancel={() => setEditingId(null)}
          onSaved={() => {
            setEditingId(null)
            message.success('改好了。')
            refresh()
          }}
        />
      )
    }

    const startsToday = dayjs(entry.startTime).isSame(selectedDate, 'day')
    const startBefore = !startsToday
    const endAfter = entry.endTime
      ? !dayjs(entry.endTime).isBefore(selectedDate.add(1, 'day'))
      : false
    const ongoing = entry.endTime === null

    return (
      <div
        key={entry.id}
        className={`entry-row ${ongoing ? 'ongoing' : ''} ${
          highlight === entry.id ? 'highlight' : ''
        }`}
        data-entry-id={entry.id}
      >
        <div
          className="entry-time"
          title={`${dayjs(entry.startTime).format('M月D日 HH:mm')}${
            entry.endTime ? ` → ${dayjs(entry.endTime).format('M月D日 HH:mm')}` : ' 起，进行中'
          }`}
        >
          {formatRange(entry.startTime, entry.endTime)}
          {ongoing ? <span className="ongoing-dot" /> : null}
        </div>
        <div className="entry-body">
          {startBefore ? (
            <span className="entry-cross">
              自 {dayjs(entry.startTime).format('M月D日 HH:mm')} 起
            </span>
          ) : null}
          {endAfter && entry.endTime ? (
            <span className="entry-cross">
              次日 {dayjs(entry.endTime).format('M月D日 HH:mm')} 止
            </span>
          ) : null}
          {entry.content}
        </div>
        <div className="entry-actions" style={deletingId === entry.id ? { opacity: 1 } : undefined}>
          {ongoing ? (
            <button
              type="button"
              className="link-action"
              onClick={() => setEndingId(endingId === entry.id ? null : entry.id)}
            >
              填写结束时间
            </button>
          ) : null}
          {deletingId === entry.id ? (
            <>
              <button type="button" className="link-action danger" onClick={() => void remove(entry)}>
                确认删除
              </button>
              <button type="button" className="link-action" onClick={() => setDeletingId(null)}>
                取消
              </button>
            </>
          ) : (
            <>
              <button type="button" className="link-action" onClick={() => setEditingId(entry.id)}>
                编辑
              </button>
              <button
                type="button"
                className="link-action danger"
                onClick={() => setDeletingId(entry.id)}
              >
                删除
              </button>
            </>
          )}
        </div>
        {endingId === entry.id ? (
          <EndTimeEditor
            entry={entry}
            onCancel={() => setEndingId(null)}
            onSaved={() => {
              setEndingId(null)
              message.success('结束时间补上了。')
              refresh()
            }}
          />
        ) : null}
      </div>
    )
  }

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1 className="page-title">时间线</h1>
          <p className="page-desc">
            你说过的事，按天放在这里。事实用实心点标记，进行中的记录会一直亮着。
          </p>
        </div>
        <div className="page-head-actions">
          <button
            type="button"
            className="ghost-btn"
            onClick={() => {
              setFormOpen((open) => !open)
              setEditingId(null)
            }}
          >
            {formOpen ? '收起新记录' : '详细记一笔'}
          </button>
        </div>
      </header>

      <div className="page-body">
        <div className="timeline-toolbar">
          <div className="date-nav">
            <button
              type="button"
              className="link-action"
              onClick={() => goToDate(selectedDate.subtract(1, 'day'))}
            >
              ← 前一天
            </button>
            <span className="date-label">
              <DatePicker
                className="date-picker"
                value={selectedDate}
                onChange={(value) => {
                  if (value) goToDate(value.startOf('day'))
                }}
                allowClear={false}
                format="M月D日"
                variant="borderless"
                suffixIcon={null}
                inputReadOnly
              />
              <small>{weekday(selectedDate.toISOString())}</small>
            </span>
            <button
              type="button"
              className="link-action"
              onClick={() => goToDate(selectedDate.add(1, 'day'))}
            >
              后一天 →
            </button>
            {!selectedDate.isSame(dayjs(), 'day') ? (
              <button type="button" className="text-action" onClick={() => goToDate(dayjs())}>
                回到今天
              </button>
            ) : null}
          </div>
          <span style={{ flex: 1 }} />
          <span className="muted" style={{ fontSize: 11 }}>
            {dayEntries.length} 条记录
            {carryover.length ? ` · 跨日 ${carryover.length} 条` : ''}
          </span>
        </div>

        <QuickCapture
          onSaved={(entry) => {
            message.success(`已记下：${entry.content.slice(0, 24)}`)
            if (!selectedDate.isSame(dayjs(entry.startTime), 'day')) {
              message.info('这条记在今天，切到「今天」就能看到。')
            }
            refresh()
          }}
        />

        {formOpen ? (
          <EntryForm
            defaultDate={selectedDate}
            onCancel={() => setFormOpen(false)}
            onSaved={() => {
              setFormOpen(false)
              message.success('记下了。')
              refresh()
            }}
          />
        ) : null}

        {entriesQuery.isLoading ? (
          <div className="answer-placeholder" style={{ paddingTop: 28 }}>
            <span className="bar" />
            <span>正在翻这一天…</span>
          </div>
        ) : entriesQuery.isError ? (
          <ErrorBlock
            title="这一天没加载出来"
            detail={
              entriesQuery.error instanceof Error
                ? entriesQuery.error.message
                : '后端可能还没启动。'
            }
            actions={
              <button type="button" className="ghost-btn" onClick={() => void entriesQuery.refetch()}>
                重试
              </button>
            }
          />
        ) : (
          <>
            <DayRuler date={selectedDate} entries={rulerEntries} onFocus={focusEntry} />
            {dayEntries.length === 0 ? (
              <div className="day-empty">
                {carryover.length > 0
                  ? '这一天没有新开始的记录，下面有从更早跨过来的条目。'
                  : '这一天还没有记录。'}
                <div className="next">
                  上面输入一句就能记下现在；
                  <button
                    type="button"
                    className="link-action"
                    onClick={() => {
                      setFormOpen(true)
                      setEditingId(null)
                    }}
                  >
                    想补过去的时间，用「详细记一笔」
                  </button>
                  。
                </div>
              </div>
            ) : (
              <div className="day-list">{dayEntries.map(renderRow)}</div>
            )}

            {endedCarryover.length > 0 ? (
              <details className="carryover">
                <summary>
                  跨日进入
                  <span className="count">{endedCarryover.length} 条</span>
                </summary>
                <div className="day-list">{endedCarryover.map(renderRow)}</div>
              </details>
            ) : null}

            {ongoingCarryover.length > 0 ? (
              <details className="carryover">
                <summary>
                  更早开始、仍未结束
                  <span className="count">{ongoingCarryover.length} 条</span>
                </summary>
                <div className="day-list">{ongoingCarryover.map(renderRow)}</div>
              </details>
            ) : null}
          </>
        )}
      </div>
    </div>
  )
}
