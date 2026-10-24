import dayjs, { type Dayjs } from 'dayjs'
import type { TimelineEntry } from '../../api/types'

const HOURS = [0, 6, 12, 18, 24]

function minuteOfDay(date: Dayjs, iso: string): number {
  return dayjs(iso).diff(date.startOf('day'), 'minute', true)
}

/**
 * 24 小时尺：把当天的记录压成一条横向刻度。
 * 跨日条目在当天被裁到 0 点或 24 点，列表里另有「跨日」标记。
 */
export function DayRuler({
  date,
  entries,
  onFocus,
}: {
  date: Dayjs
  entries: TimelineEntry[]
  onFocus: (id: number) => void
}) {
  const dayStart = date.startOf('day')
  const now = dayjs()
  const isToday = dayStart.isSame(now, 'day')
  const nowPercent = isToday ? (minuteOfDay(date, now.toISOString()) / 1440) * 100 : null

  return (
    <div className="day-ruler">
      <div className="ruler-track">
        {HOURS.map((hour) => (
          <span
            key={hour}
            className={`ruler-hour ${hour === 24 ? 'end' : ''}`}
            style={{ left: `${(hour / 24) * 100}%` }}
          >
            {hour}
          </span>
        ))}
        {entries.map((entry) => {
          const start = minuteOfDay(date, entry.startTime)
          const rawEnd = entry.endTime
            ? minuteOfDay(date, entry.endTime)
            : isToday
              ? minuteOfDay(date, now.toISOString())
              : 1440
          const from = Math.max(0, Math.min(1440, start))
          const to = Math.max(0, Math.min(1440, Math.max(rawEnd, from + 1)))
          if (to <= 0 || from >= 1440) return null
          const left = (from / 1440) * 100
          const width = Math.max(((to - from) / 1440) * 100, 0.4)
          return (
            <button
              type="button"
              key={entry.id}
              className={`ruler-block timeline ${entry.endTime ? '' : 'ongoing'}`}
              style={{
                left: `${left}%`,
                width: entry.endTime ? `${width}%` : '6px',
              }}
              title={`${dayjs(entry.startTime).format('HH:mm')} ${
                entry.endTime ? `– ${dayjs(entry.endTime).format('HH:mm')}` : '起，进行中'
              } · ${entry.content.slice(0, 40)}`}
              onClick={() => onFocus(entry.id)}
            />
          )
        })}
        {nowPercent != null ? (
          <span className="ruler-now" style={{ left: `${nowPercent}%` }} />
        ) : null}
      </div>
    </div>
  )
}
