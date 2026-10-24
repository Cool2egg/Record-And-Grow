import dayjs from 'dayjs'
import type { SourceItem, SourceKind } from '../api/types'

export function formatTurnTime(iso: string | null | undefined): string {
  if (!iso) return ''
  return dayjs(iso).format('M月D日 周六 HH:mm').replace('周六', weekday(iso))
}

export function weekday(iso: string): string {
  const names = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  return names[dayjs(iso).day()]
}

export function formatClock(iso: string | null | undefined): string {
  return iso ? dayjs(iso).format('HH:mm') : ''
}

export function formatDayTitle(date: dayjs.Dayjs): string {
  return `${date.format('M月D日')} ${weekday(date.toISOString())}`
}

export function formatFullDate(date: dayjs.Dayjs): string {
  return date.format('YYYY年M月D日')
}

export function formatRange(start: string, end: string | null): string {
  const startClock = formatClock(start)
  if (!end) return `${startClock} →`
  const endClock = formatClock(end)
  // 瞬时事件 start = end：只显示一个时刻，不写成 21:30–21:30
  if (endClock === startClock) return startClock
  return `${startClock}–${endClock}`
}

export function formatBytes(bytes: number | null | undefined): string {
  if (!bytes || bytes <= 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms)) return ''
  if (ms < 1000) return `${Math.round(ms)}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

export function clampText(value: string, max: number): string {
  const chars = Array.from(value)
  return chars.length <= max ? value : `${chars.slice(0, max).join('')}…`
}

/** 会话列表按「今天 / 昨天 / 更早的具体日期」分组。 */
export function groupConversations<T extends { updatedAt: string; createdAt: string }>(
  items: T[],
): Array<{ label: string; items: T[] }> {
  const groups = new Map<string, T[]>()
  const today = dayjs().startOf('day')
  for (const item of items) {
    const time = dayjs(item.updatedAt || item.createdAt)
    let label: string
    if (!time.isBefore(today)) label = '今天'
    else if (!time.isBefore(today.subtract(1, 'day'))) label = '昨天'
    else if (time.year() === today.year()) label = time.format('M月D日')
    else label = time.format('YYYY年M月D日')
    const bucket = groups.get(label)
    if (bucket) bucket.push(item)
    else groups.set(label, [item])
  }
  return Array.from(groups.entries()).map(([label, bucket]) => ({ label, items: bucket }))
}

export const SOURCE_META: Record<
  SourceKind,
  { char: string; name: string; color: string; routeLabel: string }
> = {
  timeline: { char: '时', name: '时间线', color: 'var(--timeline)', routeLabel: '时间线' },
  document: { char: '文', name: '文档', color: 'var(--document)', routeLabel: '文档' },
  memory: { char: '忆', name: '记忆', color: 'var(--memory)', routeLabel: '记忆' },
  graph: { char: '图', name: '图谱', color: 'var(--graph)', routeLabel: '图谱' },
}

export function sourceMeta(source: SourceItem) {
  return SOURCE_META[source.type] ?? SOURCE_META.timeline
}

export function sourceLabel(source: SourceItem): string {
  switch (source.type) {
    case 'timeline':
      return source.excerpt || '时间线记录'
    case 'document':
      return source.title ?? '文档片段'
    case 'memory':
      return source.memoryType === 'INFERRED' ? '推断记忆' : '事实记忆'
    case 'graph':
      return source.title ?? source.excerpt.split(' --')[0] ?? '图谱关系'
    default:
      return '来源'
  }
}

export function memoryTypeLabel(type: string | null | undefined): string {
  if (type === 'FACT') return '事实'
  if (type === 'INFERRED') return '推断'
  if (type === 'PENDING') return '待确认'
  return ''
}

export function degradeReasonLabel(reason: string | null | undefined): string {
  switch (reason) {
    case 'TIMEOUT':
      return '向量查询超时'
    case 'RATE_LIMITED':
      return '向量服务限流'
    case 'AUTH':
      return '向量服务鉴权失败'
    case 'UPSTREAM':
      return '向量服务异常'
    case 'BAD_RESPONSE':
      return '向量服务响应异常'
    case 'NOT_CONFIGURED':
      return '没有配置 embedding key'
    case 'DIMENSION':
      return '向量维度不一致'
    default:
      return reason ?? '未知原因'
  }
}

export function toolLabel(tool: string): string {
  switch (tool) {
    case 'search_timeline':
      return '查时间线'
    case 'get_timeline_by_date':
      return '按日期查时间线'
    case 'search_documents':
      return '查文档'
    case 'search_memory':
      return '查记忆'
    case 'search_graph':
      return '查图谱'
    case 'propose_memory':
      return '提交记忆候选'
    default:
      return tool
  }
}
