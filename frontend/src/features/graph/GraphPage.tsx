import { useEffect, useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { App as AntdApp } from 'antd'
import { Link, useSearchParams } from 'react-router-dom'
import { graphApi } from '../../api'
import type { GraphHit } from '../../api/types'
import { ErrorBlock, PageHeader } from '../../components/PageChrome'
import { clampText } from '../../lib/format'

type Position = { x: number; y: number }

function buildLayout(hits: GraphHit[], centerHint: string) {
  const nodes: string[] = []
  for (const hit of hits) {
    if (!nodes.includes(hit.source)) nodes.push(hit.source)
    if (!nodes.includes(hit.target)) nodes.push(hit.target)
  }
  if (nodes.length === 0) {
    return {
      nodes,
      positions: new Map<string, Position>(),
      center: '',
    }
  }

  const normalized = centerHint.trim().toLowerCase()
  const center =
    nodes.find((node) => node.toLowerCase() === normalized) ??
    nodes.find((node) => normalized && node.toLowerCase().includes(normalized)) ??
    nodes[0]
  const others = nodes.filter((node) => node !== center)
  const positions = new Map<string, Position>()
  positions.set(center, { x: 400, y: 208 })
  const radiusX = others.length > 6 ? 230 : 190
  const radiusY = others.length > 6 ? 150 : 140
  others.forEach((node, index) => {
    const angle = (index / Math.max(1, others.length)) * Math.PI * 2 - Math.PI / 2
    positions.set(node, {
      x: 400 + Math.cos(angle) * radiusX,
      y: 208 + Math.sin(angle) * radiusY,
    })
  })
  return { nodes, positions, center }
}

export function GraphPage() {
  const { message } = AntdApp.useApp()
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const urlEntity = searchParams.get('entity') ?? ''
  const [input, setInput] = useState(urlEntity)
  const [query, setQuery] = useState(urlEntity)
  const [selectedNode, setSelectedNode] = useState<string | null>(null)

  useEffect(() => {
    if (urlEntity !== query) {
      setInput(urlEntity)
      setQuery(urlEntity)
      setSelectedNode(null)
    }
  }, [urlEntity, query])

  const searchQuery = useQuery({
    queryKey: ['graph', query],
    queryFn: () => graphApi.search(query, 20),
    enabled: query.trim().length > 0,
    retry: false,
  })

  const statsQuery = useQuery({
    queryKey: ['graph', 'stats'],
    queryFn: graphApi.stats,
    retry: false,
  })

  const hits = searchQuery.data?.hits ?? []
  const filteredHits = useMemo(
    () =>
      selectedNode
        ? hits.filter((hit) => hit.source === selectedNode || hit.target === selectedNode)
        : hits,
    [hits, selectedNode],
  )
  const layout = useMemo(() => buildLayout(hits, query), [hits, query])

  const submit = () => {
    const value = input.trim()
    if (!value) return
    setSelectedNode(null)
    setQuery(value)
    const next = new URLSearchParams(searchParams)
    next.set('entity', value)
    setSearchParams(next)
  }

  const reindex = async () => {
    try {
      const result = await graphApi.reindex(10)
      message.success(`已重建 ${result.indexed} 篇文档的图谱。`)
      void queryClient.invalidateQueries({ queryKey: ['graph'] })
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '重建失败')
    }
  }

  return (
    <div className="page">
      <PageHeader
        title="图谱"
        description="从文档里抽出的实体关系。搜一个实体，看它一跳之内连着谁，再点回出处文档。"
        actions={
          <button type="button" className="text-action" onClick={() => void reindex()}>
            重建 10 篇
          </button>
        }
      />

      <div className="page-body">
        <div className="graph-search">
          <input
            value={input}
            onChange={(event) => setInput(event.target.value)}
            onKeyDown={(event) => {
              if (event.nativeEvent.isComposing) return
              if (event.key === 'Enter') submit()
            }}
            placeholder="搜一个实体，比如「Kafka」「深度工作」"
            aria-label="搜索图谱实体"
          />
          <button type="button" className="ghost-btn primary" onClick={submit}>
            查一跳
          </button>
        </div>

        <div className="graph-legend">
          <span>
            <i />
            节点
          </span>
          <span>
            <i style={{ background: 'transparent', border: '1px solid var(--graph)' }} />
            关系
          </span>
          {statsQuery.data ? (
            <span>
              图谱 {statsQuery.data.nodes} 节点 / {statsQuery.data.edges} 条边
              {statsQuery.data.enabled ? '' : '（已关闭）'}
            </span>
          ) : null}
          {searchQuery.data?.degraded ? (
            <span>图谱降级：{searchQuery.data.degradeReason ?? '外部存储不可用'}</span>
          ) : null}
        </div>

        {searchQuery.isError ? (
          <ErrorBlock
            title="图谱没查出来"
            detail={
              searchQuery.error instanceof Error
                ? searchQuery.error.message
                : '后端可能还没启动。'
            }
            actions={
              <button type="button" className="ghost-btn" onClick={() => void searchQuery.refetch()}>
                重试
              </button>
            }
          />
        ) : (
          <div className="graph-canvas">
            {!query ? (
              <div className="graph-empty">
                搜一个实体。
                <br />
                没有图谱的话，先上传文档，再点右上角「重建 10 篇」。
              </div>
            ) : searchQuery.isLoading ? (
              <div className="graph-empty">正在查…</div>
            ) : hits.length === 0 ? (
              <div className="graph-empty">
                「{query}」一跳之内没有关系。
                <br />
                换个实体，或者先让文档跑完图谱抽取。
              </div>
            ) : (
              <svg viewBox="0 0 800 430" role="img" aria-label="一跳关系图">
                <defs>
                  <marker
                    id="arrow"
                    viewBox="0 0 10 10"
                    refX="9"
                    refY="5"
                    markerWidth="5"
                    markerHeight="5"
                    orient="auto-start-reverse"
                  >
                    <path d="M 0 0 L 10 5 L 0 10 z" fill="var(--line)" />
                  </marker>
                </defs>
                {hits.map((hit, index) => {
                  const from = layout.positions.get(hit.source)
                  const to = layout.positions.get(hit.target)
                  if (!from || !to) return null
                  const midX = (from.x + to.x) / 2
                  const midY = (from.y + to.y) / 2
                  return (
                    <g key={`edge-${hit.edgeId}-${index}`}>
                      <line
                        x1={from.x}
                        y1={from.y}
                        x2={to.x}
                        y2={to.y}
                        stroke="var(--line)"
                        strokeWidth={1}
                        markerEnd="url(#arrow)"
                      />
                      <text x={midX} y={midY - 4} className="graph-edge-label">
                        {clampText(hit.relation, 8)}
                      </text>
                    </g>
                  )
                })}
                {layout.nodes.map((node) => {
                  const position = layout.positions.get(node)
                  if (!position) return null
                  const isCenter = node === layout.center
                  const isSelected = node === selectedNode
                  return (
                    <g
                      key={node}
                      style={{ cursor: 'pointer' }}
                      role="button"
                      tabIndex={0}
                      aria-label={`查看 ${node} 的关系`}
                      onClick={() => setSelectedNode(selectedNode === node ? null : node)}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault()
                          setSelectedNode(selectedNode === node ? null : node)
                        }
                      }}
                    >
                      <circle
                        cx={position.x}
                        cy={position.y}
                        r={isSelected ? 9 : isCenter ? 8 : 6}
                        fill={isCenter ? 'var(--moss)' : 'var(--graph)'}
                        stroke="var(--curtain)"
                        strokeWidth={2}
                      />
                      <text
                        x={position.x}
                        y={position.y + (isCenter ? 22 : 20)}
                        className="graph-node-label"
                        style={{ fontWeight: isSelected ? 600 : 400 }}
                      >
                        {clampText(node, 12)}
                      </text>
                    </g>
                  )
                })}
              </svg>
            )}
          </div>
        )}

        {query && hits.length > 0 ? (
          <div className="graph-side">
            <div className="section-title" style={{ marginTop: 22 }}>
              {selectedNode ? `「${selectedNode}」的关系` : '全部一跳关系'}
              <span className="count">{filteredHits.length} 条</span>
              {selectedNode ? (
                <button type="button" className="text-action" onClick={() => setSelectedNode(null)}>
                  看全部
                </button>
              ) : null}
            </div>
            {filteredHits.map((hit) => (
              <div className="graph-hit" key={hit.edgeId}>
                <span className="graph-hit-ref">G{hit.edgeId}</span>
                <div className="graph-hit-body">
                  {hit.source} <span className="muted">—{hit.relation}→</span> {hit.target}
                  <div className="graph-hit-doc">
                    {hit.documentId ? (
                      <Link to={`/documents?open=${hit.documentId}`}>
                        出处：{hit.title || `文档 #${hit.documentId}`}
                      </Link>
                    ) : (
                      '没有出处文档'
                    )}
                  </div>
                </div>
              </div>
            ))}
          </div>
        ) : null}
      </div>
    </div>
  )
}
