/** 与后端 DTO 一一对应的类型定义。字段名保持后端 JSON 原样。 */

export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface ChatUsage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
}

export interface Conversation {
  id: number
  title: string
  summary: string | null
  messageCount: number
  createdAt: string
  updatedAt: string
}

export type MessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM'

export interface ChatMessage {
  id: number
  role: MessageRole
  content: string
  sources: string | null
  tokenCount: number
  createdAt: string
}

export type SourceKind = 'timeline' | 'document' | 'memory' | 'graph'

export interface SourceItem {
  ref: string
  type: SourceKind
  id: number
  startTime: string | null
  endTime: string | null
  excerpt: string
  chunkId: number | null
  title: string | null
  heading: string | null
  score: number | null
  memoryType: 'FACT' | 'INFERRED' | null
  documentId: number | null
}

export interface RetrievalMeta {
  windowStart: string | null
  windowEnd: string | null
  timeBasis: string
  clamped: boolean
  matched: number
  retrieved: number
  included: number
  limitTruncated: boolean
  budgetTruncated: boolean
  keywords: string[]
  keywordFallback: boolean
  documentSearched: boolean
  documentsRetrieved: number
  documentIncluded: number
  totalIncluded: number
  documentsDegraded: boolean
  degradeReason: string | null
  memoryIncluded: number
  graphIncluded: number
}

export interface Sources {
  items: SourceItem[]
  retrieval: RetrievalMeta
}

export interface Step {
  seq: number
  tool: string
  query: string
  hits: number
  ok: boolean
  ms: number
  error: string | null
}

/** SSE 的 start 事件 */
export interface StreamStart {
  requestId: string
  conversationId: number | null
}

/** SSE 的 done 事件 */
export interface StreamDone {
  finishReason: string
  usage: ChatUsage | null
}

/** SSE 的 error 事件 */
export interface StreamError {
  code: number
  errorType: string
  message: string
  partial: boolean
  requestId: string
}

export interface ChatAskRequest {
  question: string
  conversationId?: number | null
  maxTokens?: number
  temperature?: number
}

export interface TimelineEntry {
  id: number
  content: string
  startTime: string
  endTime: string | null
  createdAt: string
  updatedAt: string
}

export interface TimelineEntryRequest {
  content: string
  startTime: string
  endTime: string | null
}

export type MemoryType = 'PENDING' | 'FACT' | 'INFERRED'

export interface MemoryEvidence {
  type: 'timeline' | 'document' | 'conversation'
  id: number | null
  excerpt: string
}

export interface Memory {
  id: number
  type: MemoryType
  proposedType: 'FACT' | 'INFERRED' | null
  content: string
  evidence: MemoryEvidence[]
  source: string
  confidence: number | null
  conversationId: number | null
  confirmedAt: string | null
  createdAt: string
  updatedAt: string
}

export type DocumentStatus = 'PENDING' | 'PARSING' | 'EMBEDDING' | 'READY' | 'FAILED'

export interface KnowledgeDocument {
  id: number
  title: string
  fileName: string
  mimeType: string
  fileSize: number
  status: DocumentStatus
  chunkCount: number | null
  charCount: number | null
  tokenCount: number | null
  chunkTruncated: boolean | null
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface DocumentUploadResponse {
  document: KnowledgeDocument
  deduplicated: boolean
}

export interface DocumentChunk {
  id: number
  chunkIndex: number
  content: string
  heading: string | null
  charStart: number | null
  charEnd: number | null
  charCount: number | null
  tokenCount: number | null
}

export interface ChunkLocator {
  chunk: DocumentChunk
  page: number
  size: number
  total: number
}

export interface GraphHit {
  edgeId: number
  source: string
  relation: string
  target: string
  documentId: number | null
  title: string | null
}

export interface GraphSearchResponse {
  hits: GraphHit[]
  searched: boolean
  degraded: boolean
  degradeReason: string | null
}

export interface GraphStats {
  nodes: number
  edges: number
  enabled: boolean
}

export interface HealthStatus {
  status: string
  database: string
  vectorExtension: boolean
}

export interface Skill {
  name: string
  description: string
  triggers: string[]
  body: string
  source: string
}

export interface SystemStatus {
  name: string
  version: string
  startedAt: string
  javaVersion: string
  aiConfigured: boolean
  embeddingConfigured: boolean
  agentEnabled: boolean
  graphEnabled: boolean
  graphStore: string
  mcpEnabled: boolean
  skills: Skill[]
}
