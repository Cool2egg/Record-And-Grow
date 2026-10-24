import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { MenuOutlined } from '@ant-design/icons'
import { NavLink, Outlet, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import dayjs from 'dayjs'
import { conversationsApi, memoriesApi, statusApi } from '../api'
import { groupConversations } from '../lib/format'
import { RailContext } from './railContext'
import './shell.css'

const NAV = [
  { to: '/', glyph: '话', label: '对话', end: true },
  { to: '/timeline', glyph: '时', label: '时间线' },
  { to: '/memory', glyph: '忆', label: '记忆' },
  { to: '/documents', glyph: '文', label: '文档' },
  { to: '/graph', glyph: '图', label: '图谱' },
  { to: '/status', glyph: '状', label: '状态' },
]

export function AppShell() {
  const location = useLocation()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [railOpen, setRailOpen] = useState(false)
  const currentConversationId = Number(searchParams.get('c')) || null
  const isChat = location.pathname === '/'
  const currentNav = NAV.find((item) =>
    item.to === '/' ? isChat : location.pathname.startsWith(item.to),
  )

  const conversations = useQuery({
    queryKey: ['conversations'],
    queryFn: () => conversationsApi.list(50),
    retry: false,
    staleTime: 10_000,
  })

  const pending = useQuery({
    queryKey: ['memories', 'pending'],
    queryFn: () => memoriesApi.pending(50),
    retry: false,
    refetchOnWindowFocus: true,
    staleTime: 15_000,
  })

  const health = useQuery({
    queryKey: ['health'],
    queryFn: statusApi.health,
    retry: false,
    refetchInterval: 30_000,
  })

  const groups = useMemo(
    () => groupConversations(conversations.data?.items ?? []),
    [conversations.data],
  )

  const closeRail = () => setRailOpen(false)
  const online = health.data?.status === 'UP'
  const railValue = useMemo(() => ({ openRail: () => setRailOpen(true) }), [])

  const selectConversation = (id: number) => {
    navigate(`/?c=${id}`)
    closeRail()
  }

  return (
    <div className="app-shell">
      {railOpen ? <div className="rail-scrim" onClick={closeRail} /> : null}
      <aside className={`rail ${railOpen ? 'open' : ''}`}>
        <div className="rail-scroll">
          <div className="brand">
            <div>
              <div className="brand-name" aria-label="RecordAndGrow">
                <b>R</b>ecord<b>A</b>nd<b>G</b>row
              </div>
              <div className="brand-sub">个人上下文记录簿</div>
            </div>
          </div>

          <nav className="nav" aria-label="主导航">
            {NAV.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.end}
                className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
                onClick={closeRail}
              >
                <span className="nav-glyph" aria-hidden>
                  {item.glyph}
                </span>
                <span>{item.label}</span>
                {item.to === '/memory' && (pending.data?.length ?? 0) > 0 ? (
                  <span className="nav-badge">{pending.data?.length}</span>
                ) : null}
              </NavLink>
            ))}
          </nav>

          <section className="rail-section rail-conversations">
              <div className="rail-section-head">
                <span className="rail-section-title">会话</span>
                <button
                  type="button"
                  className="text-action"
                  onClick={() => {
                    navigate('/')
                    closeRail()
                  }}
                >
                  新对话
                </button>
              </div>
              {conversations.isLoading ? (
                <div className="rail-note">正在翻会话…</div>
              ) : conversations.isError ? (
                <div className="rail-note">会话列表没加载出来</div>
              ) : groups.length === 0 ? (
                <div className="rail-note">
                  还没有对话。
                  <br />
                  第一句话会在这里留下一条。
                </div>
              ) : (
                groups.map((group) => (
                  <div className="conv-group" key={group.label}>
                    <div className="conv-group-label">{group.label}</div>
                    {group.items.map((conversation) => (
                      <button
                        type="button"
                        key={conversation.id}
                        className={`conv-item ${
                          conversation.id === currentConversationId ? 'active' : ''
                        }`}
                        onClick={() => selectConversation(conversation.id)}
                      >
                        <div className="conv-title">{conversation.title || '未命名会话'}</div>
                        <div className="conv-sub">
                          {dayjs(conversation.updatedAt).format('HH:mm')}
                          {conversation.messageCount > 0
                            ? ` · ${conversation.messageCount} 条`
                            : ''}
                        </div>
                      </button>
                    ))}
                  </div>
                ))
              )}
            </section>
        </div>

        <div className="rail-foot">
          <span className={`dot ${online ? '' : 'off'}`} />
          <span>{online ? '后端在线' : health.isLoading ? '连接中…' : '后端离线'}</span>
          <span className="spacer" />
          <span className="tabular" title="当前日期">
            {dayjs().format('M.D')}
          </span>
        </div>
      </aside>

      <main className="app-main">
        {!isChat ? (
          <div className="mobile-bar">
            <button
              type="button"
              className="bar-btn"
              aria-label="打开导航"
              onClick={() => setRailOpen(true)}
            >
              <MenuOutlined />
            </button>
            <span className="bar-title">{currentNav?.label ?? 'RecordAndGrow'}</span>
            <span style={{ width: 30 }} />
          </div>
        ) : null}
        <RailContext.Provider value={railValue}>
          <Outlet />
        </RailContext.Provider>
      </main>
    </div>
  )
}
