import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { App as AntdApp } from 'antd'
import dayjs from 'dayjs'
import { useSearchParams } from 'react-router-dom'
import { documentsApi } from '../../api'
import type { KnowledgeDocument } from '../../api/types'
import { ErrorBlock, PageHeader } from '../../components/PageChrome'
import { formatBytes } from '../../lib/format'
import { UploadDrop } from './UploadDrop'
import { StatusTrack } from './StatusTrack'
import { DocumentDrawer } from './DocumentDrawer'

function extensionOf(fileName: string | undefined): string {
  if (!fileName) return '—'
  const index = fileName.lastIndexOf('.')
  return index >= 0 ? fileName.slice(index + 1).toUpperCase() : '—'
}

function isActive(document: KnowledgeDocument): boolean {
  return document.status !== 'READY' && document.status !== 'FAILED'
}

export function DocumentsPage() {
  const { message } = AntdApp.useApp()
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const openId = Number(searchParams.get('open')) || null
  const chunkId = Number(searchParams.get('chunk')) || null
  const [deletingId, setDeletingId] = useState<number | null>(null)

  const documentsQuery = useQuery({
    queryKey: ['documents'],
    queryFn: () => documentsApi.list(100),
    retry: false,
    refetchInterval: (query) => {
      const data = query.state.data
      if (!data) return false
      return data.items.some(isActive) ? 3000 : false
    },
  })

  const documents = documentsQuery.data?.items ?? []
  const total = documentsQuery.data?.total ?? 0

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['documents'] })
  }

  const openDocument = (id: number, chunk?: number | null) => {
    const next = new URLSearchParams(searchParams)
    next.set('open', String(id))
    if (chunk) next.set('chunk', String(chunk))
    else next.delete('chunk')
    setSearchParams(next)
  }

  const closeDocument = () => {
    const next = new URLSearchParams(searchParams)
    next.delete('open')
    next.delete('chunk')
    setSearchParams(next)
  }

  const reindex = async (document: KnowledgeDocument) => {
    try {
      await documentsApi.reindex(document.id)
      message.success('已重新排队，稍等几秒刷新状态。')
      refresh()
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '重建失败')
    }
  }

  const remove = async (document: KnowledgeDocument) => {
    try {
      await documentsApi.remove(document.id)
      setDeletingId(null)
      if (openId === document.id) closeDocument()
      message.success('已删除。')
      refresh()
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '删除失败')
    }
  }

  return (
    <div className="page">
      <PageHeader
        title="文档"
        description="上传的资料会被切块、向量化，回答时按编号引用。处理状态和分块都能在这里核对。"
        actions={
          <button
            type="button"
            className="text-action"
            onClick={() => void documentsQuery.refetch()}
          >
            刷新
          </button>
        }
      />

      <div className="page-body">
        <UploadDrop onUploaded={refresh} />

        {documentsQuery.isLoading ? (
          <div className="answer-placeholder" style={{ paddingTop: 28 }}>
            <span className="bar" />
            <span>正在列文档…</span>
          </div>
        ) : documentsQuery.isError ? (
          <ErrorBlock
            title="文档列表没加载出来"
            detail={
              documentsQuery.error instanceof Error
                ? documentsQuery.error.message
                : '后端可能还没启动。'
            }
            actions={
              <button
                type="button"
                className="ghost-btn"
                onClick={() => void documentsQuery.refetch()}
              >
                重试
              </button>
            }
          />
        ) : documents.length === 0 ? (
          <div className="empty">
            <div className="empty-title">还没有文档</div>
            <div className="empty-hint">
              传一份 PDF 或 Markdown，问答时它就会作为「文」编号的资料被引用。
            </div>
          </div>
        ) : (
          <>
            <table className="doc-table">
              <thead>
                <tr>
                  <th style={{ width: '34%' }}>标题</th>
                  <th style={{ width: 64 }}>类型</th>
                  <th style={{ width: 168 }}>状态</th>
                  <th style={{ width: 64 }}>块数</th>
                  <th style={{ width: 110 }}>更新时间</th>
                  <th style={{ width: 150 }} />
                </tr>
              </thead>
              <tbody>
                {documents.map((document) => (
                  <tr
                    key={document.id}
                    onClick={() => openDocument(document.id)}
                    style={{ cursor: 'pointer' }}
                  >
                    <td>
                      <div className="doc-title-cell">
                        <span className="doc-title" title={document.title || document.fileName}>
                          {document.title || document.fileName}
                        </span>
                        <span className="doc-sub">
                          {document.fileName} · {formatBytes(document.fileSize)}
                          {document.chunkTruncated ? ' · 只覆盖前 2000 块' : ''}
                        </span>
                      </div>
                    </td>
                    <td>{extensionOf(document.fileName)}</td>
                    <td>
                      <StatusTrack
                        status={document.status}
                        error={document.errorMessage}
                      />
                    </td>
                    <td className="tabular">{document.chunkCount ?? '—'}</td>
                    <td className="tabular muted">
                      {dayjs(document.updatedAt).format('M月D日 HH:mm')}
                    </td>
                    <td>
                      <div
                        className="doc-actions"
                        style={deletingId === document.id ? { opacity: 1 } : undefined}
                        onClick={(event) => event.stopPropagation()}
                      >
                        <button
                          type="button"
                          className="link-action"
                          onClick={() => openDocument(document.id)}
                        >
                          查看
                        </button>
                        {document.status !== 'READY' &&
                        document.status !== 'PARSING' &&
                        document.status !== 'EMBEDDING' ? (
                          <button
                            type="button"
                            className="link-action"
                            onClick={() => void reindex(document)}
                          >
                            重建索引
                          </button>
                        ) : null}
                        {deletingId === document.id ? (
                          <>
                            <button
                              type="button"
                              className="link-action danger"
                              onClick={() => void remove(document)}
                            >
                              确认删除
                            </button>
                            <button
                              type="button"
                              className="link-action"
                              onClick={() => setDeletingId(null)}
                            >
                              取消
                            </button>
                          </>
                        ) : (
                          <button
                            type="button"
                            className="link-action danger"
                            onClick={() => setDeletingId(document.id)}
                          >
                            删除
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {total > documents.length ? (
              <div className="detail-hint">
                只显示最近 {documents.length} 篇，共 {total} 篇。删除旧文档后可在这里看到更早的。
              </div>
            ) : null}
          </>
        )}
      </div>

      <DocumentDrawer openId={openId} chunkId={chunkId} onClose={closeDocument} />
    </div>
  )
}
