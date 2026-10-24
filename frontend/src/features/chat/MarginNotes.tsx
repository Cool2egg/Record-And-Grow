import { useEffect, useState } from 'react'
import { CloseOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useNavigate } from 'react-router-dom'
import type { SourceItem, SourceKind } from '../../api/types'
import {
  degradeReasonLabel,
  formatDuration,
  memoryTypeLabel,
  sourceLabel,
  sourceMeta,
  toolLabel,
} from '../../lib/format'
import { FactMark, InferenceMark } from '../../components/Marks'
import type { ChatTurn } from './useChat'
import { navigateToSource, sourceDetail } from './CitationChip'

const RESEARCH_SOURCES: Array<{ kind: SourceKind; char: string; name: string }> = [
  { kind: 'timeline', char: '时', name: '时间线' },
  { kind: 'document', char: '文', name: '文档' },
  { kind: 'memory', char: '忆', name: '记忆' },
  { kind: 'graph', char: '图', name: '图谱' },
]

const TOOL_TO_KIND: Record<string, SourceKind> = {
  search_timeline: 'timeline',
  get_timeline_by_date: 'timeline',
  search_documents: 'document',
  search_memory: 'memory',
  search_graph: 'graph',
}

function sourceSub(source: SourceItem): string {
  if (source.type === 'timeline') return sourceDetail(source)
  if (source.type === 'document') {
    const score =
      source.score == null ? '' : `相似度 ${(source.score * 100).toFixed(0)}%`
    return [source.heading, score].filter(Boolean).join(' · ')
  }
  if (source.type === 'memory') {
    const confidence =
      source.score == null ? '' : `置信度 ${Math.round(source.score * 100)}%`
    return [memoryTypeLabel(source.memoryType), confidence].filter(Boolean).join(' · ')
  }
  if (source.type === 'graph') {
    return source.title ? `出处《${source.title}》` : '图谱三元组'
  }
  return ''
}

function SourceRow({
  source,
  index,
  onNavigate,
}: {
  source: SourceItem
  index: number
  onNavigate: (source: SourceItem) => void
}) {
  const meta = sourceMeta(source)
  const isMemory = source.type === 'memory'
  return (
    <button
      type="button"
      className="source-row"
      style={{ animationDelay: `${index * 40}ms` }}
      onClick={() => onNavigate(source)}
    >
      <span className="source-mark" style={{ color: meta.color }}>
        <i className="tick" />
        <span>{meta.char}</span>
        {isMemory && source.memoryType === 'INFERRED' ? (
          <InferenceMark color={meta.color} />
        ) : null}
        {isMemory && source.memoryType === 'FACT' ? <FactMark color={meta.color} /> : null}
      </span>
      <span className="source-body">
        <span className="source-line">
          <span className="source-ref" style={{ color: meta.color }}>
            {source.ref}
          </span>
          <span className="source-title">{sourceLabel(source)}</span>
        </span>
        {source.type !== 'timeline' ? (
          <span className="source-excerpt">{source.excerpt || '（没有摘要）'}</span>
        ) : null}
        {source.type === 'timeline' || source.type === 'graph' ? null : (
          <span className="source-sub">{sourceSub(source)}</span>
        )}
      </span>
    </button>
  )
}

export function MarginNotes({
  turn,
  streaming,
  open,
  onClose,
  onNavigate,
}: {
  turn: ChatTurn | null
  streaming: boolean
  open: boolean
  onClose: () => void
  onNavigate?: (source: SourceItem) => void
}) {
  const navigate = useNavigate()
  const handleNavigate = (source: SourceItem) => {
    onNavigate?.(source)
    navigateToSource(source, navigate)
  }

  const sources = turn?.sources
  const research = turn?.phase === 'research' || (streaming && !sources)
  const steps = turn?.steps ?? []
  const [elapsed, setElapsed] = useState(0)

  useEffect(() => {
    if (!research || !turn) {
      setElapsed(0)
      return
    }
    const startedAt = dayjs(turn.createdAt).valueOf()
    const tick = () => setElapsed(Math.max(0, Math.floor((Date.now() - startedAt) / 1000)))
    tick()
    const timer = window.setInterval(tick, 1000)
    return () => window.clearInterval(timer)
  }, [research, turn?.id, turn?.createdAt])

  const items = sources?.items ?? []
  const counts = items.reduce<Record<string, number>>((acc, item) => {
    acc[item.type] = (acc[item.type] ?? 0) + 1
    return acc
  }, {})

  const stepsByKind = new Map<SourceKind, { hits: number; ok: boolean }>()
  for (const step of steps) {
    const kind = TOOL_TO_KIND[step.tool]
    if (!kind) continue
    const previous = stepsByKind.get(kind)
    stepsByKind.set(kind, {
      hits: (previous?.hits ?? 0) + step.hits,
      ok: (previous?.ok ?? true) && step.ok,
    })
  }

  return (
    <aside className={`notes ${open ? 'open' : ''}`} aria-label="证据边注">
      <div className="notes-inner">
        <div className="notes-head">
          <span className="notes-title">边注</span>
          <button type="button" className="notes-close" onClick={onClose} aria-label="收起边注">
            <CloseOutlined />
          </button>
        </div>

        {!turn ? (
          <div className="notes-empty">
            这一页还没有证据。
            <br />
            问一个问题，或者点开上一轮，看看当时引用了什么。
          </div>
        ) : research ? (
          <>
            <div className="notes-count">
              检索中
              <small>{turn.question ? '正在找材料' : ''}</small>
            </div>
            <div className="research">
              <div className="research-label">
                四路来源
                <span className="research-elapsed">
                  {steps.length === 0 ? `仍在检索，已 ${elapsed} 秒` : `已 ${elapsed} 秒`}
                </span>
              </div>
              {RESEARCH_SOURCES.map((source) => {
                const state = stepsByKind.get(source.kind)
                return (
                  <div className="research-row" key={source.kind} data-done={state ? 'true' : 'false'}>
                    <span className="bar" />
                    <span style={{ color: 'var(--muted)', fontFamily: 'var(--kai)' }}>
                      {source.char}
                    </span>
                    <span>{source.name}</span>
                    <span className="hits">
                      {state ? `${state.hits} 条` : '检索中…'}
                    </span>
                  </div>
                )
              })}
            </div>
            {steps.length > 0 ? (
              <div className="step-list" aria-label="检索步骤">
                {steps.map((step) => (
                  <div className="step" key={step.seq}>
                    <div className="step-main">
                      <span className="step-tool">{step.tool}</span>
                      <span>{toolLabel(step.tool)}</span>
                      <span className="step-hits">
                        {step.ok ? `${step.hits} 条` : '失败'} · {formatDuration(step.ms)}
                      </span>
                    </div>
                    {step.query ? <div className="step-query">「{step.query}」</div> : null}
                    {step.error ? <div className="step-query">{step.error}</div> : null}
                  </div>
                ))}
              </div>
            ) : (
              <div className="detail-hint">
                {steps.length === 0
                  ? `检索 Agent 正在读你的记录，已经 ${elapsed} 秒。`
                  : '每一步工具调用都在上面；sources 到达后这里会换成证据列表。'}
              </div>
            )}
          </>
        ) : (
          <>
            <div className="notes-count">
              {items.length}
              <small>条证据</small>
            </div>
            <div className="notes-breakdown">
              {RESEARCH_SOURCES.filter((source) => counts[source.kind]).map((source) => (
                <span key={source.kind} style={{ color: sourceMetaKind(source.kind) }}>
                  {source.char} {counts[source.kind]}
                </span>
              ))}
              {items.length === 0 ? <span>没有带回可引用的材料</span> : null}
            </div>

            {items.length === 0 ? (
              <div className="notes-empty">
                {turn.phase === 'error' || turn.phase === 'stopped'
                  ? '这一轮检索没有走完，边注没有拿到完整证据。正文里不该被当成事实。'
                  : '这一轮没有拿到证据。'}
                <br />
                回答可能只基于会话上下文；正文里没有编号是正常的，但也不该被当成事实。
              </div>
            ) : (
              <div className="source-list">
                {items.map((source, index) => (
                  <SourceRow
                    key={`${source.ref}-${source.id}`}
                    source={source}
                    index={index}
                    onNavigate={handleNavigate}
                  />
                ))}
              </div>
            )}

            {steps.length > 0 ? (
              <details className="details">
                <summary>检索过程</summary>
                <div className="step-list" aria-label="检索步骤">
                  {steps.map((step) => (
                    <div className="step" key={step.seq}>
                      <div className="step-main">
                        <span className="step-tool">{step.tool}</span>
                        <span>{toolLabel(step.tool)}</span>
                        <span className="step-hits">
                          {step.ok ? `${step.hits} 条` : '失败'} · {formatDuration(step.ms)}
                        </span>
                      </div>
                      {step.query ? <div className="step-query">「{step.query}」</div> : null}
                    </div>
                  ))}
                </div>
              </details>
            ) : null}

            <RetrievalDetails turn={turn} />
          </>
        )}
      </div>
    </aside>
  )
}

function sourceMetaKind(kind: SourceKind): string {
  return {
    timeline: 'var(--timeline)',
    document: 'var(--document)',
    memory: 'var(--memory)',
    graph: 'var(--graph)',
  }[kind]
}

function RetrievalDetails({ turn }: { turn: ChatTurn }) {
  const retrieval = turn.sources?.retrieval
  if (!retrieval) {
    return (
      <details className="details">
        <summary>检索详情</summary>
        <div className="detail-hint">
          这一轮没有检索元信息。可能是历史消息，或者后端返回的是旧格式。
        </div>
      </details>
    )
  }

  const window =
    retrieval.windowStart && retrieval.windowEnd
      ? `${dayjs(retrieval.windowStart).format('M月D日 HH:mm')} – ${dayjs(
          retrieval.windowEnd,
        ).format('M月D日 HH:mm')}`
      : '未限定'

  const documentsRetrieved = retrieval.documentsRetrieved ?? 0
  const documentIncluded = retrieval.documentIncluded ?? 0
  const memoryIncluded = retrieval.memoryIncluded ?? 0
  const graphIncluded = retrieval.graphIncluded ?? 0
  const keywords = retrieval.keywords ?? []

  const documentText = retrieval.documentsDegraded
    ? `降级：${degradeReasonLabel(retrieval.degradeReason)}`
    : retrieval.documentSearched
      ? `查过 · ${documentsRetrieved} 条候选 / ${documentIncluded} 条纳入`
      : '这次没有查文档'

  return (
    <details className="details">
      <summary>检索详情</summary>
      <table className="detail-table">
        <tbody>
          <tr>
            <td>时间窗</td>
            <td>{window}</td>
          </tr>
          <tr>
            <td>时间线</td>
            <td>
              {retrieval.matched ?? 0} 命中 / {retrieval.retrieved ?? 0} 检索 /{' '}
              {retrieval.included ?? 0} 纳入
            </td>
          </tr>
          <tr>
            <td>文档</td>
            <td>{documentText}</td>
          </tr>
          <tr>
            <td>记忆 / 图谱</td>
            <td>
              记忆 {memoryIncluded} 条 · 图谱 {graphIncluded} 条
            </td>
          </tr>
          <tr>
            <td>截断</td>
            <td>
              {retrieval.limitTruncated ? '条数上限截断' : '未超条数'} ·{' '}
              {retrieval.budgetTruncated ? '预算截断' : '未超预算'}
            </td>
          </tr>
          <tr>
            <td>关键词</td>
            <td>{keywords.length ? keywords.join(' / ') : '无'}</td>
          </tr>
        </tbody>
      </table>
      {retrieval.documentSearched && documentsRetrieved === 0 ? (
        <div className="detail-hint">文档里没查到相关内容。</div>
      ) : null}
      {retrieval.clamped ? (
        <div className="detail-hint">时间窗被配置上限夹紧过。</div>
      ) : null}
    </details>
  )
}
