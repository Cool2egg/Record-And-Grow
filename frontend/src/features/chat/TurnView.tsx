import { Link } from 'react-router-dom'
import { CHAT_ERROR_COPY, type ChatErrorType } from '../../lib/chatErrors'
import { formatTurnTime } from '../../lib/format'
import { ErrorBlock } from '../../components/PageChrome'
import { AnswerMarkdown } from './AnswerMarkdown'
import type { ChatTurn } from './useChat'

export function TurnView({
  turn,
  active,
  onFocus,
  onRetry,
  onContinue,
}: {
  turn: ChatTurn
  active: boolean
  onFocus: () => void
  onRetry: (turn: ChatTurn) => void
  onContinue: (turn: ChatTurn) => void
}) {
  const streaming = turn.phase === 'research' || turn.phase === 'generating'
  const errorType = turn.error?.type ?? 'UNKNOWN'
  const info = CHAT_ERROR_COPY[errorType as ChatErrorType] ?? CHAT_ERROR_COPY.UNKNOWN

  return (
    <article className={`turn ${active ? 'active' : ''}`} data-turn-id={turn.id}>
      {turn.question ? (
        <button type="button" className="turn-question" onClick={onFocus}>
          <span className="turn-time" style={{ display: 'block' }}>
            {formatTurnTime(turn.createdAt)}
          </span>
          <span className="turn-question-text" style={{ display: 'block' }}>
            {turn.question}
          </span>
        </button>
      ) : null}

      {turn.answer ? (
        <AnswerMarkdown
          content={turn.answer}
          sources={turn.sources}
          streaming={turn.phase === 'generating'}
        />
      ) : streaming ? (
        <div className="answer-placeholder">
          <span className="bar" />
          <span>{turn.phase === 'research' ? '正在查记录…' : '正在写…'}</span>
        </div>
      ) : turn.phase === 'done' ? (
        <div className="answer-note">这一轮没有留下回答。可能是流中断了，刷新也不会补回来。</div>
      ) : null}

      {turn.phase === 'error' ? (
        <ErrorBlock
          title={info.title}
          detail={
            <>
              {info.detail}
              {turn.error?.message && turn.error.message !== info.detail ? (
                <span>（{turn.error.message}）</span>
              ) : null}
            </>
          }
          actions={
            <>
              {info.retry ? (
                <button type="button" className="ghost-btn primary" onClick={() => onRetry(turn)}>
                  重试
                </button>
              ) : null}
              {info.continueAsk ? (
                <button type="button" className="ghost-btn primary" onClick={() => onContinue(turn)}>
                  接着问
                </button>
              ) : null}
              {info.configLink ? <Link to="/status">去状态页看看</Link> : null}
              {!info.retry && !info.continueAsk && !info.configLink ? (
                <button
                  type="button"
                  className="ghost-btn"
                  onClick={() => onContinue(turn)}
                >
                  把问题放回输入框
                </button>
              ) : null}
            </>
          }
        />
      ) : null}

      {turn.phase === 'stopped' ? (
        <div className="answer-note">
          已停止生成。这一轮不会写入会话记录；
          <button type="button" className="link-action" onClick={() => onContinue(turn)}>
            接着问
          </button>
          可把已写出的内容续上。
        </div>
      ) : null}
    </article>
  )
}
