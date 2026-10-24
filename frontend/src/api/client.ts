import type { ApiEnvelope, DocumentUploadResponse, PageResult, Sources } from './types'

const API_BASE = '/api'

/** 统一错误：HTTP 状态 + 业务 code + 原始 message。 */
export class ApiError extends Error {
  status: number
  code?: number
  errorType?: string
  partial?: boolean
  requestId?: string

  constructor(message: string, status: number, code?: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

async function readEnvelope<T>(response: Response): Promise<ApiEnvelope<T> | null> {
  const text = await response.text()
  if (!text) return null
  try {
    return JSON.parse(text) as ApiEnvelope<T>
  } catch {
    return null
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init?.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
      ...init?.headers,
    },
  })
  const envelope = await readEnvelope<T>(response)
  if (!response.ok || !envelope || envelope.code !== 0) {
    if (response.status === 503 && !envelope) {
      throw new ApiError('后端暂时不可用', response.status)
    }
    throw new ApiError(
      envelope?.message || `请求失败（${response.status}）`,
      response.status,
      envelope?.code,
    )
  }
  return envelope.data
}

export function apiGet<T>(path: string): Promise<T> {
  return request<T>(path)
}

export function apiPost<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, {
    method: 'POST',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

export function apiPut<T>(path: string, body: unknown): Promise<T> {
  return request<T>(path, {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export function apiDelete<T = void>(path: string): Promise<T> {
  return request<T>(path, { method: 'DELETE' })
}

export function apiGetPage<T>(path: string): Promise<PageResult<T>> {
  return apiGet<PageResult<T>>(path)
}

/** 上传需要进度，单独走 XHR；响应结构仍是 ApiResponse。 */
export function uploadDocument(
  file: File,
  onProgress: (percent: number) => void,
): Promise<DocumentUploadResponse> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `${API_BASE}/documents`)
    xhr.responseType = 'text'
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) {
        onProgress(Math.round((event.loaded / event.total) * 100))
      }
    }
    xhr.onload = () => {
      let envelope: ApiEnvelope<DocumentUploadResponse> | null = null
      try {
        envelope = xhr.responseText ? JSON.parse(xhr.responseText) : null
      } catch {
        envelope = null
      }
      if (xhr.status >= 200 && xhr.status < 300 && envelope?.code === 0) {
        onProgress(100)
        resolve(envelope.data)
        return
      }
      reject(
        new ApiError(
          envelope?.message || `上传失败（${xhr.status}）`,
          xhr.status,
          envelope?.code,
        ),
      )
    }
    xhr.onerror = () => reject(new ApiError('上传中断，检查网络后重试', 0))
    const form = new FormData()
    form.append('file', file)
    xhr.send(form)
  })
}

/**
 * 兼容旧数据：早期持久化的 sources 用 epoch 秒写时间，新数据用 ISO-8601。
 * 统一归一化成 ISO，避免历史消息的边注跳到 1970 年。
 */
function normalizeTime(value: unknown): string | null {
  if (value == null) return null
  if (typeof value === 'number' && Number.isFinite(value)) {
    return new Date(value > 1e12 ? value : value * 1000).toISOString()
  }
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (!trimmed) return null
    if (/^\d+(\.\d+)?$/.test(trimmed)) {
      const number = Number(trimmed)
      return new Date(number > 1e12 ? number : number * 1000).toISOString()
    }
    return value
  }
  return null
}

/** 历史消息里的 sources 是 JSON 字符串，解析失败时返回 null 而不是崩掉整页。 */
export function parseSources(raw: string | null | undefined): Sources | null {
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as Sources
    if (!parsed || !Array.isArray(parsed.items)) return null
    parsed.items = parsed.items.map((item) => ({
      ...item,
      startTime: normalizeTime(item.startTime),
      endTime: normalizeTime(item.endTime),
    }))
    if (parsed.retrieval) {
      parsed.retrieval = {
        ...parsed.retrieval,
        windowStart: normalizeTime(parsed.retrieval.windowStart),
        windowEnd: normalizeTime(parsed.retrieval.windowEnd),
      }
    }
    return parsed
  } catch {
    return null
  }
}
