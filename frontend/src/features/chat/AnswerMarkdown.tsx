import { isValidElement, type ReactNode } from 'react'
import Markdown, { defaultUrlTransform, type Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import type { Sources, SourceItem } from '../../api/types'
import { CitationChip } from './CitationChip'

const LETTER_TO_KIND = {
  T: 'timeline',
  D: 'document',
  M: 'memory',
  G: 'graph',
} as const

const KIND_TO_CHAR = {
  timeline: '时',
  document: '文',
  memory: '忆',
  graph: '图',
} as const

/**
 * 只把 sources 里真实存在的编号换成小片；模型偶尔编的编号原样保留，
 * 正好把「它引用了不存在的东西」暴露出来。
 */
function transformCitations(markdown: string, allowed: Set<string>): string {
  const segments = markdown.split(/(```[\s\S]*?```|`[^`\n]*`)/g)
  return segments
    .map((segment, index) => {
      if (index % 2 === 1) return segment
      return segment.replace(/\[([TDMG])(\d+)\]/g, (match, letter: string, number: string) => {
        const ref = `${letter}${number}`
        if (!allowed.has(ref)) return match
        const kind = LETTER_TO_KIND[letter as keyof typeof LETTER_TO_KIND]
        return `[${KIND_TO_CHAR[kind]}${number}](#cite:${ref})`
      })
    })
    .join('')
}

function containsCitation(node: ReactNode): boolean {
  if (node === null || node === undefined || typeof node === 'boolean') return false
  if (typeof node === 'string' || typeof node === 'number') return false
  if (Array.isArray(node)) return node.some(containsCitation)
  if (isValidElement(node)) {
    // 自定义 a 渲染器返回的是 CitationChip 组件，还没有落到 DOM 的 button.cite 上，
    // 所以这里必须先认组件类型，否则带引用的段落会被误标成「推断」。
    if (node.type === CitationChip) return true
    const props = node.props as { className?: string; children?: ReactNode }
    if (typeof props.className === 'string' && props.className.split(/\s+/).includes('cite')) {
      return true
    }
    return containsCitation(props.children)
  }
  return false
}

function textLength(node: ReactNode): number {
  if (node === null || node === undefined || typeof node === 'boolean') return 0
  if (typeof node === 'string' || typeof node === 'number') return String(node).length
  if (Array.isArray(node)) return node.reduce((sum, child) => sum + textLength(child), 0)
  if (isValidElement(node)) {
    return textLength((node.props as { children?: ReactNode }).children)
  }
  return 0
}

export function AnswerMarkdown({
  content,
  sources,
  streaming,
}: {
  content: string
  sources: Sources | null
  streaming: boolean
}) {
  const allowed = new Set((sources?.items ?? []).map((item) => item.ref))
  const sourceMap = new Map((sources?.items ?? []).map((item) => [item.ref, item]))
  const markdown = transformCitations(content, allowed)

  const components: Components = {
    a({ href, children }) {
      if (href?.startsWith('#cite:')) {
        const ref = href.slice('#cite:'.length)
        const source: SourceItem | undefined = sourceMap.get(ref)
        if (source) return <CitationChip source={source} />
        return <span className="cite-unknown">[{ref}]</span>
      }
      const external = Boolean(href && /^https?:\/\//i.test(href))
      return (
        <a
          href={href}
          target={external ? '_blank' : undefined}
          rel={external ? 'noopener noreferrer' : undefined}
        >
          {children}
        </a>
      )
    },
    p({ children }) {
      const cited = containsCitation(children)
      const length = textLength(children)
      const inferred = !cited && length >= 12
      return <p className={inferred ? 'inference' : undefined}>{children}</p>
    },
    table({ children }) {
      return (
        <div className="md-table-wrap">
          <table>{children}</table>
        </div>
      )
    },
  }

  return (
    <div className={`answer ${streaming ? 'is-streaming' : ''}`}>
      <Markdown
        remarkPlugins={[remarkGfm]}
        components={components}
        urlTransform={(url) => (url.startsWith('#cite:') ? url : defaultUrlTransform(url))}
      >
        {markdown}
      </Markdown>
    </div>
  )
}
