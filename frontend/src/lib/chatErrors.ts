import { ApiError } from '../api/client'

export type ChatErrorType =
  | 'NOT_CONFIGURED'
  | 'AUTHENTICATION'
  | 'INSUFFICIENT_BALANCE'
  | 'RATE_LIMITED'
  | 'TIMEOUT'
  | 'NETWORK'
  | 'UPSTREAM_ERROR'
  | 'STREAM_INTERRUPTED'
  | 'BAD_RESPONSE'
  | 'INVALID_REQUEST'
  | 'UNKNOWN'

export interface ChatErrorInfo {
  title: string
  detail: string
  retry: boolean
  continueAsk: boolean
  configLink: boolean
}

export const CHAT_ERROR_COPY: Record<ChatErrorType, ChatErrorInfo> = {
  NOT_CONFIGURED: {
    title: '还没配置模型 key',
    detail: '检索和生成都不可用。先在运行环境里设置 DEEPSEEK_API_KEY 与 DASHSCOPE_API_KEY，再重启后端。',
    retry: false,
    continueAsk: false,
    configLink: true,
  },
  AUTHENTICATION: {
    title: 'key 无效',
    detail: '模型服务拒绝了这次请求。检查 key 是否填错、是否已过期，然后重启后端。',
    retry: false,
    continueAsk: false,
    configLink: true,
  },
  INSUFFICIENT_BALANCE: {
    title: '额度不足',
    detail: '模型服务的余额不够了。充值后再试，已经生成的回答会保留。',
    retry: false,
    continueAsk: false,
    configLink: true,
  },
  RATE_LIMITED: {
    title: '请求太频繁',
    detail: '模型服务正在限流，等几秒再试。问题原文还在输入框里。',
    retry: true,
    continueAsk: false,
    configLink: false,
  },
  TIMEOUT: {
    title: '模型暂时没响应',
    detail: '这次请求超时了。可以重试，或者把问题缩短一点再问。',
    retry: true,
    continueAsk: false,
    configLink: false,
  },
  NETWORK: {
    title: '连接断了',
    detail: '没有收到完整的回答。检查后端是否还在运行，或者网络是否抖动。',
    retry: true,
    continueAsk: false,
    configLink: false,
  },
  UPSTREAM_ERROR: {
    title: '模型服务暂时不可用',
    detail: '上游返回了异常，不是你的问题。稍后重试即可。',
    retry: true,
    continueAsk: false,
    configLink: false,
  },
  STREAM_INTERRUPTED: {
    title: '回答中断了',
    detail: '已经写出来的部分会保留。可以接着问，让回答续上。',
    retry: false,
    continueAsk: true,
    configLink: false,
  },
  BAD_RESPONSE: {
    title: '回答中断了',
    detail: '模型返回的内容不完整。已经写出来的部分会保留，可以接着问。',
    retry: false,
    continueAsk: true,
    configLink: false,
  },
  INVALID_REQUEST: {
    title: '问题太长了',
    detail: '问题限制在 64–4096 tokens。删掉一些背景，再发一次。',
    retry: false,
    continueAsk: false,
    configLink: false,
  },
  UNKNOWN: {
    title: '这次没答上来',
    detail: '发生了未分类的错误。问题原文保留在输入框里，可以直接重试。',
    retry: true,
    continueAsk: false,
    configLink: false,
  },
}

export function toErrorType(value: string | null | undefined): ChatErrorType {
  if (!value) return 'UNKNOWN'
  return value in CHAT_ERROR_COPY ? (value as ChatErrorType) : 'UNKNOWN'
}

/** 把 HTTP 层的非 SSE 失败翻译成同一条错误矩阵。 */
export function fromApiError(error: unknown): { type: ChatErrorType; message: string } {
  if (error instanceof ApiError) {
    if (error.status === 503) {
      // 50300 既可能是没配 key，也可能是数据库/代理不可用；
      // 文案保持「模型暂时不可用」，同时给配置入口，避免误导。
      return { type: 'UPSTREAM_ERROR', message: error.message }
    }
    if (error.status === 401 || error.status === 403) {
      return { type: 'AUTHENTICATION', message: error.message }
    }
    if (error.status === 429) {
      return { type: 'RATE_LIMITED', message: error.message }
    }
    if (error.status === 400) {
      return { type: 'INVALID_REQUEST', message: error.message }
    }
    return { type: 'UNKNOWN', message: error.message }
  }
  if (error instanceof Error) {
    if (error.name === 'AbortError') return { type: 'STREAM_INTERRUPTED', message: error.message }
    return { type: 'NETWORK', message: error.message }
  }
  return { type: 'UNKNOWN', message: '未知错误' }
}
