import { apiDelete, apiGet, apiPost, apiPut, uploadDocument } from './client'
import type {
  ChatMessage,
  ChunkLocator,
  Conversation,
  DocumentChunk,
  GraphSearchResponse,
  GraphStats,
  HealthStatus,
  KnowledgeDocument,
  Memory,
  MemoryType,
  PageResult,
  Skill,
  SystemStatus,
  TimelineEntry,
  TimelineEntryRequest,
} from './types'

export const conversationsApi = {
  list: (size = 50) =>
    apiGet<PageResult<Conversation>>(`/conversations?page=1&size=${size}`),
  messages: (id: number, size = 100) =>
    apiGet<PageResult<ChatMessage>>(`/conversations/${id}/messages?page=1&size=${size}`),
  create: (title?: string) => apiPost<Conversation>('/conversations', { title }),
  remove: (id: number) => apiDelete<void>(`/conversations/${id}`),
}

export const timelineApi = {
  list: (start: string, end: string, size = 100) => {
    const params = new URLSearchParams({ start, end, page: '1', size: String(size) })
    return apiGet<PageResult<TimelineEntry>>(`/timeline?${params.toString()}`)
  },
  get: (id: number) => apiGet<TimelineEntry>(`/timeline/${id}`),
  create: (body: TimelineEntryRequest) => apiPost<TimelineEntry>('/timeline', body),
  update: (id: number, body: TimelineEntryRequest) =>
    apiPut<TimelineEntry>(`/timeline/${id}`, body),
  remove: (id: number) => apiDelete<void>(`/timeline/${id}`),
}

export const memoriesApi = {
  pending: (limit = 50) => apiGet<Memory[]>(`/memories/pending?limit=${limit}`),
  list: (type: MemoryType, size = 100) =>
    apiGet<PageResult<Memory>>(`/memories?type=${type}&page=1&size=${size}`),
  confirm: (id: number, body?: { content?: string; type?: 'FACT' | 'INFERRED' }) =>
    apiPost<Memory>(`/memories/${id}/confirm`, body),
  reject: (id: number) => apiPost<void>(`/memories/${id}/reject`),
  remove: (id: number) => apiDelete<void>(`/memories/${id}`),
}

export const documentsApi = {
  list: (size = 100) =>
    apiGet<PageResult<KnowledgeDocument>>(`/documents?page=1&size=${size}`),
  get: (id: number) => apiGet<KnowledgeDocument>(`/documents/${id}`),
  chunks: (id: number, page: number, size = 20) =>
    apiGet<PageResult<DocumentChunk>>(`/documents/${id}/chunks?page=${page}&size=${size}`),
  locateChunk: (id: number, chunkId: number, size = 20) =>
    apiGet<ChunkLocator>(`/documents/${id}/chunks/${chunkId}?size=${size}`),
  reindex: (id: number) => apiPost<void>(`/documents/${id}/reindex`),
  remove: (id: number) => apiDelete<void>(`/documents/${id}`),
  upload: uploadDocument,
}

export const graphApi = {
  search: (q: string, limit = 20) => {
    const params = new URLSearchParams({ q, limit: String(limit) })
    return apiGet<GraphSearchResponse>(`/graph/search?${params.toString()}`)
  },
  stats: () => apiGet<GraphStats>('/graph/stats'),
  reindex: (limit = 10) => apiPost<{ indexed: number }>(`/graph/reindex?limit=${limit}`),
}

export const statusApi = {
  health: () => apiGet<HealthStatus>('/health'),
  system: () => apiGet<SystemStatus>('/system'),
  skills: () => apiGet<Skill[]>('/skills'),
  reloadSkills: () => apiPost<{ loaded: number }>('/skills/reload'),
}
