import type { DocumentStatus } from '../../api/types'

const STAGES: Array<{ status: DocumentStatus; label: string }> = [
  { status: 'PENDING', label: '已入库' },
  { status: 'PARSING', label: '解析中' },
  { status: 'EMBEDDING', label: '向量化' },
  { status: 'READY', label: '可检索' },
]

export function StatusTrack({
  status,
  error,
}: {
  status: DocumentStatus
  error?: string | null
}) {
  if (status === 'FAILED') {
    return (
      <div>
        <div className="status-track">
          {STAGES.map((stage) => (
            <i className="status-seg failed" key={stage.status} />
          ))}
          <span className="status-label">失败</span>
        </div>
        {error ? <div className="doc-error">{error}</div> : null}
      </div>
    )
  }

  const current = Math.max(
    0,
    STAGES.findIndex((stage) => stage.status === status),
  )
  return (
    <div className="status-track" title={STAGES[current]?.label}>
      {STAGES.map((stage, index) => (
        <i
          key={stage.status}
          className={`status-seg ${
            index < current ? 'done' : index === current ? 'current' : ''
          }`}
        />
      ))}
      <span className="status-label">{STAGES[current]?.label}</span>
    </div>
  )
}
