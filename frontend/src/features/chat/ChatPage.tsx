import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { EditOutlined, MenuOutlined } from '@ant-design/icons'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { conversationsApi, statusApi } from '../../api'
import { ErrorBlock } from '../../components/PageChrome'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { useRail } from '../../app/railContext'
import { MarginNotes } from './MarginNotes'
import { TurnView } from './TurnView'
import { Composer } from './Composer'
import { useChat, type ChatTurn } from './useChat'

const SUGGESTIONS = [
  '上个月我跑了多少公里？',
  '根据我上传的笔记，总结一下深度工作的要点',
  '我最近在准备什么？',
]

export function ChatPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { openRail } = useRail()
  const isNarrow = useMediaQuery('(max-width: 1100px)')

  const urlConversationId = Number(searchParams.get('c')) || null
  const [conversationId, setConversationId] = useState<number | null>(urlConversationId)
  const [historyConversationId, setHistoryConversationId] = useState<number | null>(
    urlConversationId,
  )
  const [composer, setComposer] = useState('')
  const [notesOpen, setNotesOpen] = useState(false)
  const [preflightError, setPreflightError] = useState<string | null>(null)

  const conversationIdRef = useRef(conversationId)
  const threadRef = useRef<HTMLDivElement | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)
  const nearBottomRef = useRef(true)
  const initialFocusRef = useRef(false)

  const scrollToBottom = (behavior: ScrollBehavior) => {
    const node = threadRef.current
    if (!node) return
    node.scrollTo({ top: node.scrollHeight, behavior })
  }

  const systemQuery = useQuery({
    queryKey: ['system'],
    queryFn: statusApi.system,
    retry: false,
    staleTime: 60_000,
  })

  const messagesQuery = useQuery({
    queryKey: ['messages', historyConversationId],
    queryFn: () => conversationsApi.messages(historyConversationId as number, 100),
    enabled: historyConversationId != null,
    retry: false,
    staleTime: 0,
  })

  const handleConversationCreated = useCallback(
    (id: number) => {
      conversationIdRef.current = id
      setConversationId(id)
      setSearchParams(
        (previous) => {
          const next = new URLSearchParams(previous)
          next.set('c', String(id))
          return next
        },
        { replace: true },
      )
      void queryClient.invalidateQueries({ queryKey: ['conversations'] })
    },
    [queryClient, setSearchParams],
  )

  const handleSettled = useCallback(
    (id: number | null) => {
      if (id) {
        void queryClient.invalidateQueries({ queryKey: ['conversations'] })
      }
    },
    [queryClient],
  )

  const chat = useChat({
    conversationId,
    onConversationCreated: handleConversationCreated,
    onSettled: handleSettled,
  })

  useEffect(() => {
    conversationIdRef.current = conversationId
  }, [conversationId])

  // URL 是选中会话的唯一来源；新建会话时先更新 state 再更新 URL，这里会跳过重置。
  useEffect(() => {
    if (urlConversationId === conversationIdRef.current) return
    conversationIdRef.current = urlConversationId
    setConversationId(urlConversationId)
    setHistoryConversationId(urlConversationId)
    setNotesOpen(false)
    setPreflightError(null)
    setComposer('')
    chat.reset(urlConversationId)
  }, [urlConversationId, chat.reset])

  useEffect(() => {
    if (historyConversationId && messagesQuery.data && !chat.isStreaming) {
      chat.loadHistory(messagesQuery.data.items)
      requestAnimationFrame(() => scrollToBottom('auto'))
    }
    // 故意不把 isStreaming 放进依赖：流结束后不要再用服务端历史覆盖本轮的本地状态
    // （停止生成时服务端不会保存半截回答）
  }, [messagesQuery.data, historyConversationId, chat.loadHistory])

  const onThreadScroll = () => {
    const node = threadRef.current
    if (!node) return
    nearBottomRef.current =
      node.scrollHeight - node.scrollTop - node.clientHeight < 140
  }

  const lastTurn = chat.turns.at(-1)

  useEffect(() => {
    if (nearBottomRef.current) {
      scrollToBottom(chat.isStreaming ? 'auto' : 'smooth')
    }
  }, [lastTurn?.answer, lastTurn?.steps.length, lastTurn?.phase, chat.isStreaming])

  useEffect(() => {
    if (chat.turns.length > 0) {
      nearBottomRef.current = true
      scrollToBottom('smooth')
    }
  }, [chat.turns.length])

  useEffect(() => {
    if (!initialFocusRef.current && !isNarrow && chat.turns.length === 0) {
      initialFocusRef.current = true
      textareaRef.current?.focus()
    }
  }, [isNarrow, chat.turns.length])

  const focusComposer = () => {
    requestAnimationFrame(() => textareaRef.current?.focus())
  }

  const handleSend = () => {
    const question = composer.trim()
    if (!question || chat.isStreaming) return
    if (historyConversationId != null && messagesQuery.isLoading) return
    if (systemQuery.data && !systemQuery.data.aiConfigured) {
      setPreflightError('模型 key 还没配置，检索和生成都不可用。')
      return
    }
    setPreflightError(null)
    setComposer('')
    void chat.send(question)
    focusComposer()
  }

  const handleRetry = (turn: ChatTurn) => {
    if (chat.isStreaming) return
    setPreflightError(null)
    void chat.send(turn.question)
    focusComposer()
  }

  const handleContinue = (turn: ChatTurn) => {
    if (turn.error?.type === 'INVALID_REQUEST' || !turn.answer) {
      setComposer(turn.question)
    } else {
      const partial = turn.answer.trim().slice(-500)
      setComposer(
        `接着上一轮继续回答。你已经写到：\n\n${partial}\n\n请从这里继续，不要重复已经写过的内容。`,
      )
    }
    focusComposer()
  }

  const useSuggestion = (text: string) => {
    setComposer(text)
    focusComposer()
  }

  const newChat = () => {
    navigate('/')
    setComposer('')
    focusComposer()
  }

  const focusTurn = (turn: ChatTurn) => {
    chat.focusTurn(turn.id)
    if (isNarrow) setNotesOpen(true)
  }

  const loadingHistory = historyConversationId != null && messagesQuery.isLoading
  const historyError = historyConversationId != null && messagesQuery.isError
  const noModelKey = systemQuery.data ? !systemQuery.data.aiConfigured : false
  const notesCount = chat.activeTurn?.sources?.items.length
  const research = chat.activeTurn?.phase === 'research'

  return (
    <div className="chat-layout">
      <div className="chat-main">
        <div className="mobile-bar">
          <button type="button" className="bar-btn" aria-label="打开导航" onClick={openRail}>
            <MenuOutlined />
          </button>
          <span className="bar-title">对话</span>
          <button type="button" className="bar-btn" aria-label="新对话" onClick={newChat}>
            <EditOutlined />
          </button>
        </div>

        <div className="thread-scroll" ref={threadRef} onScroll={onThreadScroll}>
          <div className="thread">
            {noModelKey || preflightError ? (
              <ErrorBlock
                title="还没配置模型 key"
                detail={
                  preflightError ??
                  '检索和生成都不可用。先在运行环境里设置 DEEPSEEK_API_KEY 与 DASHSCOPE_API_KEY，再重启后端。'
                }
                actions={<Link to="/status">去状态页看看</Link>}
              />
            ) : null}

            {loadingHistory ? (
              <div className="answer-placeholder" style={{ paddingTop: 20 }}>
                <span className="bar" />
                <span>正在翻会话…</span>
              </div>
            ) : historyError ? (
              <ErrorBlock
                title="这个会话没加载出来"
                detail={
                  messagesQuery.error instanceof Error
                    ? messagesQuery.error.message
                    : '后端可能还没启动，或者这条会话已经被删了。'
                }
                actions={
                  <>
                    <button
                      type="button"
                      className="ghost-btn"
                      onClick={() => void messagesQuery.refetch()}
                    >
                      重试
                    </button>
                    <Link to="/">开一个新对话</Link>
                  </>
                }
              />
            ) : chat.turns.length === 0 ? (
              <div className="chat-intro">
                <div className="seal-line">记录簿 · 对话</div>
                <h1>把今天记下来，剩下的交给它。</h1>
                <p className="intro-copy">
                  它不只看聊天记录：你的时间线、上传的文档、确认过的记忆和文档里的图谱，
                  都会被拿出来核对。回答旁边有边注，证据会标编号。
                </p>
                <div className="suggestion-list">
                  {SUGGESTIONS.map((suggestion) => (
                    <button
                      type="button"
                      className="suggestion"
                      key={suggestion}
                      onClick={() => useSuggestion(suggestion)}
                    >
                      {suggestion}
                    </button>
                  ))}
                </div>
              </div>
            ) : (
              chat.turns.map((turn) => (
                <TurnView
                  key={turn.id}
                  turn={turn}
                  active={chat.activeTurn?.id === turn.id}
                  onFocus={() => focusTurn(turn)}
                  onRetry={handleRetry}
                  onContinue={handleContinue}
                />
              ))
            )}
          </div>
        </div>

        <div className="composer-dock">
          <Composer
            value={composer}
            onChange={setComposer}
            onSend={handleSend}
            onStop={chat.stop}
            streaming={chat.isStreaming}
            disabled={noModelKey || (historyConversationId != null && messagesQuery.isLoading)}
            textareaRef={textareaRef}
          />
        </div>

        <button
          type="button"
          className="notes-toggle"
          onClick={() => setNotesOpen(true)}
          aria-label="打开证据边注"
        >
          {research ? '检索中' : notesCount != null ? `证据 ${notesCount}` : '边注'}
        </button>
      </div>

      <MarginNotes
        turn={chat.activeTurn}
        streaming={chat.isStreaming}
        open={!isNarrow || notesOpen}
        onClose={() => setNotesOpen(false)}
      />

      {isNarrow && notesOpen ? (
        <div className="notes-scrim" onClick={() => setNotesOpen(false)} />
      ) : null}
    </div>
  )
}
