import { useCallback, useEffect, useReducer, useRef } from 'react'
import { parseSources } from '../../api/client'
import type { ChatMessage, ChatUsage, Sources, Step } from '../../api/types'
import { fromApiError, toErrorType, type ChatErrorType } from '../../lib/chatErrors'
import { openChatStream, readSseStream } from './sse'

export type TurnPhase = 'research' | 'generating' | 'done' | 'error' | 'stopped'

export interface ChatTurn {
  id: string
  question: string
  createdAt: string
  answer: string
  sources: Sources | null
  steps: Step[]
  phase: TurnPhase
  error: { type: ChatErrorType; message: string } | null
  requestId: string | null
  conversationId: number | null
  usage: ChatUsage | null
}

interface ChatState {
  turns: ChatTurn[]
  activeTurnId: string | null
  streamingTurnId: string | null
  conversationId: number | null
}

type Action =
  | { type: 'reset'; conversationId: number | null }
  | { type: 'load'; turns: ChatTurn[] }
  | { type: 'start'; turn: ChatTurn }
  | { type: 'stream-start'; turnId: string; requestId: string; conversationId: number | null }
  | { type: 'step'; turnId: string; step: Step }
  | { type: 'sources'; turnId: string; sources: Sources }
  | { type: 'delta'; turnId: string; content: string }
  | { type: 'done'; turnId: string; usage: ChatUsage | null }
  | { type: 'error'; turnId: string; errorType: ChatErrorType; message: string }
  | { type: 'stopped'; turnId: string }
  | { type: 'focus'; turnId: string }

const initialState = (conversationId: number | null): ChatState => ({
  turns: [],
  activeTurnId: null,
  streamingTurnId: null,
  conversationId,
})

function patchTurn(state: ChatState, turnId: string, patch: Partial<ChatTurn>): ChatState {
  return {
    ...state,
    turns: state.turns.map((turn) => (turn.id === turnId ? { ...turn, ...patch } : turn)),
  }
}

function reducer(state: ChatState, action: Action): ChatState {
  switch (action.type) {
    case 'reset':
      return initialState(action.conversationId)
    case 'load': {
      const lastWithSources = [...action.turns]
        .reverse()
        .find((turn) => turn.sources)?.id
      return {
        ...initialState(state.conversationId),
        turns: action.turns,
        activeTurnId: lastWithSources ?? action.turns.at(-1)?.id ?? null,
      }
    }
    case 'start':
      return {
        ...state,
        turns: [...state.turns, action.turn],
        activeTurnId: action.turn.id,
        streamingTurnId: action.turn.id,
      }
    case 'stream-start':
      return {
        ...patchTurn(state, action.turnId, {
          requestId: action.requestId,
          conversationId: action.conversationId,
        }),
        conversationId: action.conversationId ?? state.conversationId,
      }
    case 'step': {
      const turn = state.turns.find((item) => item.id === action.turnId)
      if (!turn || turn.steps.some((step) => step.seq === action.step.seq)) return state
      return patchTurn(state, action.turnId, { steps: [...turn.steps, action.step] })
    }
    case 'sources':
      return {
        ...patchTurn(state, action.turnId, {
          sources: action.sources,
          phase: 'generating',
        }),
        activeTurnId: action.turnId,
      }
    case 'delta': {
      const turn = state.turns.find((item) => item.id === action.turnId)
      if (!turn) return state
      return patchTurn(state, action.turnId, {
        answer: turn.answer + action.content,
        phase: 'generating',
      })
    }
    case 'done':
      return {
        ...patchTurn(state, action.turnId, {
          phase: 'done',
          usage: action.usage,
        }),
        streamingTurnId: state.streamingTurnId === action.turnId ? null : state.streamingTurnId,
      }
    case 'error':
      return {
        ...patchTurn(state, action.turnId, {
          phase: 'error',
          error: { type: action.errorType, message: action.message },
        }),
        streamingTurnId: state.streamingTurnId === action.turnId ? null : state.streamingTurnId,
      }
    case 'stopped':
      return {
        ...patchTurn(state, action.turnId, { phase: 'stopped' }),
        streamingTurnId: state.streamingTurnId === action.turnId ? null : state.streamingTurnId,
      }
    case 'focus':
      return { ...state, activeTurnId: action.turnId }
    default:
      return state
  }
}

/** 历史消息配对成「一问一答」。没有回答的轮次保留一条空回答，提示它没有落库。 */
export function turnsFromMessages(messages: ChatMessage[]): ChatTurn[] {
  const turns: ChatTurn[] = []
  for (const message of messages) {
    if (message.role === 'USER') {
      turns.push({
        id: `history-${message.id}`,
        question: message.content,
        createdAt: message.createdAt,
        answer: '',
        sources: null,
        steps: [],
        phase: 'done',
        error: null,
        requestId: null,
        conversationId: null,
        usage: null,
      })
      continue
    }
    if (message.role !== 'ASSISTANT') continue
    let turn = turns.at(-1)
    if (!turn) {
      turn = {
        id: `history-${message.id}`,
        question: '',
        createdAt: message.createdAt,
        answer: '',
        sources: null,
        steps: [],
        phase: 'done',
        error: null,
        requestId: null,
        conversationId: null,
        usage: null,
      }
      turns.push(turn)
    }
    turn.answer = message.content
    turn.sources = parseSources(message.sources)
  }
  return turns
}

interface UseChatOptions {
  conversationId: number | null
  onConversationCreated: (id: number) => void
  onSettled: (conversationId: number | null) => void
}

const STALL_MS = 40_000

export function useChat({ conversationId, onConversationCreated, onSettled }: UseChatOptions) {
  const [state, dispatch] = useReducer(reducer, conversationId, initialState)
  const conversationRef = useRef(conversationId)
  const streamingRef = useRef(false)
  const abortRef = useRef<AbortController | null>(null)
  const stoppedByUserRef = useRef(false)
  const createdRef = useRef(onConversationCreated)
  const settledRef = useRef(onSettled)

  createdRef.current = onConversationCreated
  settledRef.current = onSettled

  useEffect(() => {
    conversationRef.current = conversationId
  }, [conversationId])

  useEffect(() => {
    streamingRef.current = state.streamingTurnId !== null
  }, [state.streamingTurnId])

  const loadHistory = useCallback((messages: ChatMessage[]) => {
    dispatch({ type: 'load', turns: turnsFromMessages(messages) })
  }, [])

  const reset = useCallback((id: number | null) => {
    abortRef.current?.abort()
    abortRef.current = null
    stoppedByUserRef.current = false
    dispatch({ type: 'reset', conversationId: id })
  }, [])

  const focusTurn = useCallback((turnId: string) => {
    dispatch({ type: 'focus', turnId })
  }, [])

  const stop = useCallback(() => {
    const active = abortRef.current
    if (!active) return
    stoppedByUserRef.current = true
    active.abort()
  }, [])

  const send = useCallback(async (rawQuestion: string) => {
    const question = rawQuestion.trim()
    if (!question || streamingRef.current) return

    const turnId = `local-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
    const createdAt = new Date().toISOString()
    stoppedByUserRef.current = false
    streamingRef.current = true
    dispatch({
      type: 'start',
      turn: {
        id: turnId,
        question,
        createdAt,
        answer: '',
        sources: null,
        steps: [],
        phase: 'research',
        error: null,
        requestId: null,
        conversationId: conversationRef.current,
        usage: null,
      },
    })

    const controller = new AbortController()
    abortRef.current = controller
    let stalled = false
    let lastActivity = Date.now()
    let receivedDone = false
    let receivedError = false

    const watchdog = window.setInterval(() => {
      if (Date.now() - lastActivity > STALL_MS) {
        stalled = true
        controller.abort()
      }
    }, 2_000)

    try {
      const body = await openChatStream(
        {
          question,
          conversationId: conversationRef.current ?? undefined,
        },
        controller.signal,
      )

      await readSseStream(
        body,
        (event, payload) => {
          lastActivity = Date.now()
          if (!payload || typeof payload !== 'object') return
          switch (event) {
            case 'start': {
              const data = payload as { requestId: string; conversationId: number | null }
              dispatch({
                type: 'stream-start',
                turnId,
                requestId: data.requestId,
                conversationId: data.conversationId ?? null,
              })
              if (data.conversationId && data.conversationId !== conversationRef.current) {
                conversationRef.current = data.conversationId
                createdRef.current(data.conversationId)
              }
              break
            }
            case 'step': {
              dispatch({ type: 'step', turnId, step: payload as Step })
              break
            }
            case 'sources': {
              dispatch({ type: 'sources', turnId, sources: payload as Sources })
              break
            }
            case 'delta': {
              const data = payload as { content?: string }
              if (data.content) {
                dispatch({ type: 'delta', turnId, content: data.content })
              }
              break
            }
            case 'done': {
              const data = payload as { usage?: ChatUsage | null }
              receivedDone = true
              dispatch({ type: 'done', turnId, usage: data.usage ?? null })
              break
            }
            case 'error': {
              const data = payload as {
                errorType?: string
                message?: string
              }
              receivedError = true
              dispatch({
                type: 'error',
                turnId,
                errorType: toErrorType(data.errorType),
                message: data.message ?? '',
              })
              break
            }
            default:
              // 未知事件忽略：后端以后加事件不需要同步改前端
              break
          }
        },
        () => {
          lastActivity = Date.now()
        },
      )

      if (!receivedDone && !receivedError && !controller.signal.aborted) {
        dispatch({
          type: 'error',
          turnId,
          errorType: 'STREAM_INTERRUPTED',
          message: '连接在 done 之前结束了',
        })
      }
    } catch (error) {
      if (stoppedByUserRef.current) {
        dispatch({ type: 'stopped', turnId })
      } else if (stalled) {
        dispatch({
          type: 'error',
          turnId,
          errorType: 'NETWORK',
          message: '超过 40 秒没有收到任何数据，连接好像断了。',
        })
      } else if (controller.signal.aborted) {
        dispatch({ type: 'stopped', turnId })
      } else {
        const mapped = fromApiError(error)
        dispatch({
          type: 'error',
          turnId,
          errorType: mapped.type,
          message: mapped.message,
        })
      }
    } finally {
      window.clearInterval(watchdog)
      // 切换会话时旧请求的 finally 可能晚于新请求开始；只清理自己持有的 controller
      if (abortRef.current === controller) {
        abortRef.current = null
        stoppedByUserRef.current = false
      }
      settledRef.current(conversationRef.current)
    }
  }, [])

  const activeTurn =
    state.turns.find((turn) => turn.id === state.activeTurnId) ?? state.turns.at(-1) ?? null
  const isStreaming = state.streamingTurnId !== null

  return {
    turns: state.turns,
    activeTurn,
    isStreaming,
    conversationId: state.conversationId,
    send,
    stop,
    reset,
    loadHistory,
    focusTurn,
  }
}
