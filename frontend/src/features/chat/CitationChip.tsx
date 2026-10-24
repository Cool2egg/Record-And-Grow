import { Popover } from 'antd'
import dayjs from 'dayjs'
import { useNavigate } from 'react-router-dom'
import type { SourceItem } from '../../api/types'
import { formatClock, memoryTypeLabel, sourceLabel, sourceMeta } from '../../lib/format'

export function sourceDetail(source: SourceItem): string {
  if (source.type === 'timeline' && source.startTime) {
    const start = dayjs(source.startTime).format('M月D日 HH:mm')
    return source.endTime
      ? `${start}–${formatClock(source.endTime)}`
      : `${start} 起，进行中`
  }
  if (source.type === 'document') {
    return [source.title, source.heading].filter(Boolean).join(' · ') || '文档片段'
  }
  if (source.type === 'memory') {
    const confidence =
      source.score == null ? '' : `置信度 ${Math.round(source.score * 100)}%`
    return [memoryTypeLabel(source.memoryType), confidence].filter(Boolean).join(' · ')
  }
  if (source.type === 'graph') {
    return source.title ? `出处《${source.title}》` : '知识图谱'
  }
  return ''
}

export function navigateToSource(source: SourceItem, navigate: ReturnType<typeof useNavigate>) {
  switch (source.type) {
    case 'timeline': {
      const date = source.startTime ? dayjs(source.startTime).format('YYYY-MM-DD') : ''
      navigate(`/timeline?date=${date}&focus=${source.id}`)
      return
    }
    case 'document': {
      const params = new URLSearchParams({ open: String(source.id) })
      if (source.chunkId) params.set('chunk', String(source.chunkId))
      navigate(`/documents?${params.toString()}`)
      return
    }
    case 'memory':
      navigate(`/memory?focus=${source.id}`)
      return
    case 'graph': {
      const entity = source.excerpt.split(' --')[0] || source.title || ''
      navigate(`/graph?entity=${encodeURIComponent(entity)}`)
      return
    }
    default:
      return
  }
}

export function RefBadge({ source }: { source: SourceItem }) {
  const meta = sourceMeta(source)
  return (
    <span className="source-ref" style={{ color: meta.color }}>
      {meta.char}
      {source.ref.slice(1)}
    </span>
  )
}

export function CitationChip({ source }: { source: SourceItem }) {
  const navigate = useNavigate()
  const meta = sourceMeta(source)
  const label = `${meta.char}${source.ref.slice(1)}`

  const content = (
    <div className="cite-pop">
      <div className="cite-pop-title">
        <i className="tick" style={{ background: meta.color, height: 11 }} />
        <span>
          {meta.name} · {sourceLabel(source)}
        </span>
      </div>
      <div className="cite-pop-meta">{sourceDetail(source)}</div>
      <div className="cite-pop-text">{source.excerpt || '这一条没有可展开的原文。'}</div>
      <div className="cite-pop-meta">点击跳到对应记录</div>
    </div>
  )

  return (
    <Popover content={content} mouseEnterDelay={0.18} placement="top" destroyOnHidden>
      <button
        type="button"
        className="cite"
        style={{ ['--cite-color' as string]: meta.color }}
        onClick={() => navigateToSource(source, navigate)}
        aria-label={`来源 ${meta.name} ${source.ref}`}
      >
        {label}
      </button>
    </Popover>
  )
}
