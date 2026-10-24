import { ApiError } from '../../api/client'
import type { ChatAskRequest } from '../../api/types'

export type SseHandler = (event: string, data: unknown) => void

/**
 * 逐块解析 SSE。
 *
 * 必须处理：事件以空行分隔、多行 data 拼接、`: ping` 注释、
 * `\r\n` 与跨 chunk 切断的中文（TextDecoder stream: true）。
 * 未知事件直接忽略，后端以后加事件不用改前端。
 */
export async function readSseStream(
  body: ReadableStream<Uint8Array>,
  onEvent: SseHandler,
  onActivity: () => void,
): Promise<void> {
  const reader = body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  const dispatch = (raw: string) => {
    if (!raw.trim()) return
    let event = 'message'
    const dataLines: string[] = []
    for (const line of raw.split(/\r\n|\n|\r/)) {
      if (!line || line.startsWith(':')) continue
      if (line.startsWith('event:')) {
        event = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).replace(/^ /, ''))
      }
    }
    if (dataLines.length === 0) return
    const data = dataLines.join('\n')
    let payload: unknown = data
    try {
      payload = JSON.parse(data)
    } catch {
      // 不是 JSON 的事件（理论上是后端 bug）当文本处理，交给调用方忽略
    }
    onEvent(event, payload)
  }

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      onActivity()
      buffer += decoder.decode(value, { stream: true })
      let boundary = buffer.search(/\r\n\r\n|\n\n|\r\r/)
      while (boundary >= 0) {
        const separator = buffer.match(/\r\n\r\n|\n\n|\r\r/)![0]
        const raw = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + separator.length)
        dispatch(raw)
        boundary = buffer.search(/\r\n\r\n|\n\n|\r\r/)
      }
    }
    buffer += decoder.decode()
    dispatch(buffer)
  } finally {
    reader.releaseLock()
  }
}

/** 打开 SSE 流；非 2xx 时按统一 ApiResponse 解析并抛出 ApiError。 */
export async function openChatStream(
  body: ChatAskRequest,
  signal: AbortSignal,
): Promise<ReadableStream<Uint8Array>> {
  const response = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
    signal,
  })

  if (!response.ok) {
    let message = `请求失败（${response.status}）`
    let code: number | undefined
    try {
      const text = await response.text()
      if (text) {
        const envelope = JSON.parse(text) as { code?: number; message?: string }
        code = envelope.code
        if (envelope.message) message = envelope.message
      }
    } catch {
      // 保留默认文案
    }
    throw new ApiError(message, response.status, code)
  }

  if (!response.body) {
    throw new ApiError('浏览器没有拿到可读的流', 0)
  }
  return response.body
}
