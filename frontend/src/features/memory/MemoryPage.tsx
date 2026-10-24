import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { App as AntdApp } from 'antd'
import dayjs from 'dayjs'
import { Link, useSearchParams } from 'react-router-dom'
import { memoriesApi } from '../../api'
import type { Memory, MemoryEvidence } from '../../api/types'
import { ErrorBlock, PageHeader } from '../../components/PageChrome'
import { FactMark, InferenceMark, PendingMark } from '../../components/Marks'
import { memoryTypeLabel } from '../../lib/format'

function evidenceLabel(type: string): string {
  if (type === 'timeline') return '时间线'
  if (type === 'document') return '文档'
  if (type === 'conversation') return '对话'
  return type
}

function sourceLabel(source: string): string {
  if (source === 'USER') return '手动记录'
  if (source === 'AI_EXTRACTED') return '对话提炼'
  if (source === 'AI_DOCUMENT') return '文档提炼'
  return source
}

function EvidenceList({ evidence }: { evidence: MemoryEvidence[] }) {
  if (!evidence.length) {
    return <div className="memory-meta">这条没有留下证据摘录。</div>
  }
  return (
    <details className="pending-evidence">
      <summary>证据 {evidence.length} 条</summary>
      <ul>
        {evidence.map((item, index) => (
          <li key={`${item.type}-${item.id}-${index}`}>
            <span className="memory-source">{evidenceLabel(item.type)}</span>
            {item.excerpt || '（无摘录）'}
          </li>
        ))}
      </ul>
    </details>
  )
}

function PendingCard({
  memory,
  busy,
  editing,
  draft,
  highlighted,
  onEdit,
  onDraft,
  onCancelEdit,
  onConfirm,
  onReject,
}: {
  memory: Memory
  busy: boolean
  editing: boolean
  draft: string
  highlighted: boolean
  onEdit: () => void
  onDraft: (value: string) => void
  onCancelEdit: () => void
  onConfirm: (type?: 'FACT') => void
  onReject: () => void
}) {
  const proposed =
    memory.proposedType === 'FACT'
      ? '事实'
      : memory.proposedType === 'INFERRED'
        ? '推断'
        : '事实（默认）'
  return (
    <div
      className={`pending-card ${highlighted ? 'highlight' : ''}`}
      data-memory-id={memory.id}
    >
      <div className="pending-meta">
        <PendingMark />
        <span className="pending-type">
          <span style={{ fontFamily: 'var(--kai)' }}>忆</span>
          候选 · 拟入「{proposed}」
        </span>
        <span>{dayjs(memory.createdAt).format('M月D日 HH:mm')}</span>
      </div>

      {editing ? (
        <div className="memory-edit">
          <textarea
            value={draft}
            onChange={(event) => onDraft(event.target.value)}
            aria-label="修改候选记忆"
            autoFocus
          />
          <div className="pending-actions">
            <button
              type="button"
              className="ghost-btn primary"
              disabled={busy || !draft.trim()}
              onClick={() => onConfirm()}
            >
              {busy ? '确认中…' : '用这版确认'}
            </button>
            <button type="button" className="text-action" onClick={onCancelEdit}>
              取消编辑
            </button>
          </div>
        </div>
      ) : (
        <>
          <div className="pending-content">{memory.content}</div>
          <EvidenceList evidence={memory.evidence} />
          <div className="pending-actions">
            <button
              type="button"
              className="ghost-btn primary"
              disabled={busy}
              onClick={() => onConfirm()}
            >
              {busy ? '确认中…' : '确认'}
            </button>
            {memory.proposedType === 'INFERRED' ? (
              <button
                type="button"
                className="ghost-btn"
                disabled={busy}
                onClick={() => onConfirm('FACT')}
              >
                改成事实
              </button>
            ) : null}
            <button type="button" className="link-action" disabled={busy} onClick={onEdit}>
              改措辞
            </button>
            <button
              type="button"
              className="link-action danger"
              disabled={busy}
              onClick={onReject}
            >
              拒绝
            </button>
          </div>
        </>
      )}
    </div>
  )
}

function ConfirmedItem({
  memory,
  deleting,
  highlighted,
  onDelete,
  onCancelDelete,
}: {
  memory: Memory
  deleting: boolean
  highlighted: boolean
  onDelete: () => void
  onCancelDelete: () => void
}) {
  const inferred = memory.type === 'INFERRED'
  return (
    <div
      className={`memory-item ${highlighted ? 'highlight' : ''}`}
      data-memory-id={memory.id}
    >
      <span className="memory-mark">
        {inferred ? <InferenceMark color="var(--memory)" /> : <FactMark color="var(--memory)" />}
      </span>
      <div className="memory-main">
        <div className="memory-content">{memory.content}</div>
        <div className="memory-meta">
          <span>{memoryTypeLabel(memory.type)}</span>
          <span>来源 {sourceLabel(memory.source)}</span>
          {memory.confirmedAt ? (
            <span>{dayjs(memory.confirmedAt).format('M月D日')} 确认</span>
          ) : null}
          {memory.confidence != null ? (
            <span>置信度 {Math.round(memory.confidence * 100)}%</span>
          ) : null}
          {memory.conversationId ? (
            <Link to={`/?c=${memory.conversationId}`}>回到对话</Link>
          ) : null}
        </div>
        <EvidenceList evidence={memory.evidence} />
      </div>
      <div className="memory-delete" style={deleting ? { opacity: 1 } : undefined}>
        {deleting ? (
          <>
            <button type="button" className="link-action danger" onClick={onDelete}>
              确认删除
            </button>
            <button type="button" className="link-action" onClick={onCancelDelete}>
              取消
            </button>
          </>
        ) : (
          <button type="button" className="link-action danger" onClick={onDelete}>
            删除
          </button>
        )}
      </div>
    </div>
  )
}

export function MemoryPage() {
  const { message } = AntdApp.useApp()
  const queryClient = useQueryClient()
  const [searchParams] = useSearchParams()
  const focusId = Number(searchParams.get('focus')) || null

  const [editingId, setEditingId] = useState<number | null>(null)
  const [draft, setDraft] = useState('')
  const [busyId, setBusyId] = useState<number | null>(null)
  const [deletingId, setDeletingId] = useState<number | null>(null)
  const [highlight, setHighlight] = useState<number | null>(null)

  const pendingQuery = useQuery({
    queryKey: ['memories', 'pending'],
    queryFn: () => memoriesApi.pending(50),
    retry: false,
    refetchOnWindowFocus: true,
  })

  const factQuery = useQuery({
    queryKey: ['memories', 'list', 'FACT'],
    queryFn: () => memoriesApi.list('FACT', 100),
    retry: false,
  })

  const inferredQuery = useQuery({
    queryKey: ['memories', 'list', 'INFERRED'],
    queryFn: () => memoriesApi.list('INFERRED', 100),
    retry: false,
  })

  const pending = pendingQuery.data ?? []
  const facts = factQuery.data?.items ?? []
  const inferred = inferredQuery.data?.items ?? []

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['memories'] })
  }

  useEffect(() => {
    if (!focusId) {
      setHighlight(null)
      return
    }
    const node = document.querySelector(`[data-memory-id="${focusId}"]`)
    if (!node) return
    node.scrollIntoView({ behavior: 'smooth', block: 'center' })
    setHighlight(focusId)
    const timer = window.setTimeout(() => setHighlight(null), 2200)
    return () => window.clearTimeout(timer)
  }, [focusId, pending.length, facts.length, inferred.length])

  const confirm = async (memory: Memory, type?: 'FACT') => {
    setBusyId(memory.id)
    try {
      const body =
        editingId === memory.id
          ? { content: draft.trim(), ...(type ? { type } : {}) }
          : type
            ? { type }
            : undefined
      await memoriesApi.confirm(memory.id, body)
      setEditingId(null)
      setDraft('')
      message.success(type === 'FACT' ? '已确认，并记为事实。' : '已确认，之后回答会引用它。')
      refresh()
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '确认失败')
    } finally {
      setBusyId(null)
    }
  }

  const reject = async (memory: Memory) => {
    setBusyId(memory.id)
    try {
      await memoriesApi.reject(memory.id)
      message.success('已拒绝，这条候选不会再出现。')
      refresh()
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '拒绝失败')
    } finally {
      setBusyId(null)
    }
  }

  const remove = async (memory: Memory) => {
    try {
      await memoriesApi.remove(memory.id)
      setDeletingId(null)
      message.success('已删除。')
      refresh()
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '删除失败')
    }
  }

  const loading =
    pendingQuery.isLoading || factQuery.isLoading || inferredQuery.isLoading
  const failed = pendingQuery.isError || factQuery.isError || inferredQuery.isError
  const error =
    (pendingQuery.error as Error | undefined) ??
    (factQuery.error as Error | undefined) ??
    (inferredQuery.error as Error | undefined)

  return (
    <div className="page">
      <PageHeader
        title="记忆"
        description="AI 只能写候选；只有你确认过的，才会进入回答。事实与推断分开摆，不混在一起。"
      />
      <div className="page-body">
        {failed ? (
          <ErrorBlock
            title="记忆没加载出来"
            detail={error?.message ?? '后端可能还没启动。'}
            actions={
              <button
                type="button"
                className="ghost-btn"
                onClick={() => {
                  void pendingQuery.refetch()
                  void factQuery.refetch()
                  void inferredQuery.refetch()
                }}
              >
                重试
              </button>
            }
          />
        ) : loading ? (
          <div className="answer-placeholder" style={{ paddingTop: 24 }}>
            <span className="bar" />
            <span>正在翻记忆…</span>
          </div>
        ) : (
          <div className="memory-grid">
            <section>
              <div className="memory-col-head">
                <span className="memory-col-title">待确认</span>
                <span className="memory-col-count">{pending.length} 条候选</span>
              </div>
              {pending.length === 0 ? (
                <div className="memory-empty">
                  收件箱是空的。AI 从对话里提炼出的候选会先落到这里，确认后才参与回答。
                </div>
              ) : (
                pending.map((memory) => (
                  <PendingCard
                    key={memory.id}
                    memory={memory}
                    busy={busyId === memory.id}
                    editing={editingId === memory.id}
                    draft={draft}
                    highlighted={highlight === memory.id}
                    onEdit={() => {
                      setEditingId(memory.id)
                      setDraft(memory.content)
                    }}
                    onDraft={setDraft}
                    onCancelEdit={() => {
                      setEditingId(null)
                      setDraft('')
                    }}
                    onConfirm={(type) => void confirm(memory, type)}
                    onReject={() => void reject(memory)}
                  />
                ))
              )}
            </section>

            <section>
              <div className="memory-col-head">
                <span className="memory-col-title">已确认</span>
                <span className="memory-col-count">
                  事实 {factQuery.data?.total ?? facts.length} · 推断{' '}
                  {inferredQuery.data?.total ?? inferred.length}
                </span>
              </div>

              <div className="memory-group">
                <div className="memory-group-title">
                  <FactMark color="var(--memory)" />
                  事实
                  <span className="memory-group-count">{facts.length}</span>
                </div>
                {facts.length === 0 ? (
                  <div className="memory-empty" style={{ paddingTop: 8 }}>
                    还没有确认过的事实。
                  </div>
                ) : (
                  facts.map((memory) => (
                    <ConfirmedItem
                      key={memory.id}
                      memory={memory}
                      deleting={deletingId === memory.id}
                      highlighted={highlight === memory.id}
                      onDelete={() =>
                        deletingId === memory.id
                          ? void remove(memory)
                          : setDeletingId(memory.id)
                      }
                      onCancelDelete={() => setDeletingId(null)}
                    />
                  ))
                )}
              </div>

              <div className="memory-group">
                <div className="memory-group-title">
                  <InferenceMark color="var(--memory)" />
                  推断
                  <span className="memory-group-count">{inferred.length}</span>
                </div>
                {inferred.length === 0 ? (
                  <div className="memory-empty" style={{ paddingTop: 8 }}>
                    还没有确认过的推断。
                  </div>
                ) : (
                  inferred.map((memory) => (
                    <ConfirmedItem
                      key={memory.id}
                      memory={memory}
                      deleting={deletingId === memory.id}
                      highlighted={highlight === memory.id}
                      onDelete={() =>
                        deletingId === memory.id
                          ? void remove(memory)
                          : setDeletingId(memory.id)
                      }
                      onCancelDelete={() => setDeletingId(null)}
                    />
                  ))
                )}
              </div>
            </section>
          </div>
        )}
      </div>
    </div>
  )
}
