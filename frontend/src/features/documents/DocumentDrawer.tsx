import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Drawer } from 'antd'
import dayjs from 'dayjs'
import { documentsApi } from '../../api'
import { formatBytes } from '../../lib/format'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { ErrorBlock } from '../../components/PageChrome'
import { StatusTrack } from './StatusTrack'

function extensionOf(fileName: string | undefined): string {
  if (!fileName) return '文件'
  const index = fileName.lastIndexOf('.')
  return index >= 0 ? fileName.slice(index + 1).toUpperCase() : '文件'
}

export function DocumentDrawer({
  openId,
  chunkId,
  onClose,
}: {
  openId: number | null
  chunkId: number | null
  onClose: () => void
}) {
  const [page, setPage] = useState(1)
  const [highlight, setHighlight] = useState<number | null>(null)
  const isNarrow = useMediaQuery('(max-width: 900px)')

  const documentQuery = useQuery({
    queryKey: ['document', openId],
    queryFn: () => documentsApi.get(openId as number),
    enabled: openId != null,
    retry: false,
  })

  const locatorQuery = useQuery({
    queryKey: ['document-chunk-locator', openId, chunkId],
    queryFn: () => documentsApi.locateChunk(openId as number, chunkId as number, 20),
    enabled: openId != null && chunkId != null,
    retry: false,
  })

  useEffect(() => {
    setPage(1)
    setHighlight(null)
  }, [openId])

  useEffect(() => {
    if (locatorQuery.data) {
      setPage(locatorQuery.data.page)
      setHighlight(chunkId)
    }
  }, [locatorQuery.data, chunkId])

  const chunksQuery = useQuery({
    queryKey: ['document-chunks', openId, page],
    queryFn: () => documentsApi.chunks(openId as number, page, 20),
    // 有 chunkId 时先等定位结果，避免先闪第一页再跳页
    enabled:
      openId != null &&
      (chunkId == null || locatorQuery.isSuccess || locatorQuery.isError),
    retry: false,
  })

  useEffect(() => {
    if (!highlight) return
    const node = document.querySelector(`[data-chunk-id="${highlight}"]`)
    if (!node) return
    node.scrollIntoView({ behavior: 'smooth', block: 'center' })
    const timer = window.setTimeout(() => setHighlight(null), 1500)
    return () => window.clearTimeout(timer)
  }, [highlight, chunksQuery.data])

  const doc = documentQuery.data
  const chunks = chunksQuery.data
  const total = chunks?.total ?? 0
  const pageCount = Math.max(1, Math.ceil(total / 20))

  return (
    <Drawer
      open={openId != null}
      onClose={onClose}
      size={isNarrow ? '100%' : 760}
      title={doc?.title || '文档详情'}
      destroyOnHidden
      className="document-drawer"
    >
      {documentQuery.isError ? (
        <div style={{ padding: 24 }}>
          <ErrorBlock
            title="文档没加载出来"
            detail={
              documentQuery.error instanceof Error
                ? documentQuery.error.message
                : '它可能已经被删了。'
            }
            actions={
              <button type="button" className="ghost-btn" onClick={() => void documentQuery.refetch()}>
                重试
              </button>
            }
          />
        </div>
      ) : !doc ? (
        <div style={{ padding: 24 }} className="answer-placeholder">
          <span className="bar" />
          <span>正在打开文档…</span>
        </div>
      ) : (
        <div className="drawer-layout">
          <aside className="drawer-meta">
            <div className="drawer-meta-title">{doc.title || doc.fileName}</div>
            <div className="drawer-meta-list">
              <div>
                <span>类型</span>
                <b>{extensionOf(doc.fileName)}</b>
              </div>
              <div>
                <span>大小</span>
                <b>{formatBytes(doc.fileSize)}</b>
              </div>
              <div>
                <span>字符 / token</span>
                <b>
                  {doc.charCount ?? '—'} / {doc.tokenCount ?? '—'}
                </b>
              </div>
              <div>
                <span>分块数</span>
                <b>{doc.chunkCount ?? '—'}</b>
              </div>
              <div>
                <span>更新时间</span>
                <b>{dayjs(doc.updatedAt).format('M月D日 HH:mm')}</b>
              </div>
            </div>

            <div style={{ marginTop: 16 }}>
              <StatusTrack status={doc.status} error={doc.errorMessage} />
            </div>

            {doc.chunkTruncated ? (
              <div className="detail-hint" style={{ marginTop: 14 }}>
                这篇只覆盖前 2000 块，检索结果可能不完整。
              </div>
            ) : null}
            {doc.errorMessage && doc.status !== 'FAILED' ? (
              <div className="doc-error" style={{ marginTop: 10 }}>
                {doc.errorMessage}
              </div>
            ) : null}
            {doc.status !== 'READY' ? (
              <div className="detail-hint" style={{ marginTop: 14 }}>
                文档还在处理。列表每 3 秒刷新一次，全部就绪后会自动停。
              </div>
            ) : null}
          </aside>

          <section className="drawer-chunks">
            <div className="drawer-pager" style={{ marginTop: 0 }}>
              <span>分块 · 共 {total} 块</span>
              <span>
                第 {page} / {pageCount} 页
              </span>
            </div>

            {chunkId && locatorQuery.isError ? (
              <div className="detail-hint" style={{ marginTop: 10 }}>
                引用的块没定位到，可能已经重建过索引。下面是第一页。
              </div>
            ) : null}

            {chunksQuery.isLoading ? (
              <div className="answer-placeholder" style={{ paddingTop: 22 }}>
                <span className="bar" />
                <span>正在翻分块…</span>
              </div>
            ) : chunksQuery.isError ? (
              <ErrorBlock
                title="分块没加载出来"
                detail={
                  chunksQuery.error instanceof Error ? chunksQuery.error.message : '稍后再试。'
                }
                actions={
                  <button type="button" className="ghost-btn" onClick={() => void chunksQuery.refetch()}>
                    重试
                  </button>
                }
              />
            ) : chunks && chunks.items.length > 0 ? (
              <>
                {chunks.items.map((chunk) => (
                  <article
                    key={chunk.id}
                    className={`chunk-item ${highlight === chunk.id ? 'highlight' : ''}`}
                    data-chunk-id={chunk.id}
                  >
                    <div className="chunk-head">
                      <span>#{chunk.chunkIndex}</span>
                      <span className="chunk-heading">
                        {chunk.heading || '（无标题）'} · {chunk.charCount ?? 0} 字
                      </span>
                    </div>
                    <div className="chunk-content">{chunk.content}</div>
                  </article>
                ))}
                <div className="drawer-pager">
                  <button
                    type="button"
                    className="ghost-btn"
                    disabled={page <= 1}
                    onClick={() => setPage((value) => Math.max(1, value - 1))}
                  >
                    上一页
                  </button>
                  <span>
                    第 {page} / {pageCount} 页
                  </span>
                  <button
                    type="button"
                    className="ghost-btn"
                    disabled={page >= pageCount}
                    onClick={() => setPage((value) => Math.min(pageCount, value + 1))}
                  >
                    下一页
                  </button>
                </div>
              </>
            ) : (
              <div className="memory-empty">
                还没有可查看的分块。文档可能仍在解析，或者解析失败了。
              </div>
            )}
          </section>
        </div>
      )}
    </Drawer>
  )
}
