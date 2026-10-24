import type { CSSProperties, ReactNode } from 'react'

export function FactMark({ color = 'var(--moss)' }: { color?: string }) {
  return <span className="mark mark-fact" style={{ color }} aria-hidden />
}

export function InferenceMark({ color = 'var(--moss)' }: { color?: string }) {
  return <span className="mark mark-inferred" style={{ color }} aria-hidden />
}

export function PendingMark({ color = 'var(--memory)' }: { color?: string }) {
  return <span className="mark mark-pending" style={{ color }} aria-hidden />
}

export function SourceTag({
  char,
  color,
  children,
}: {
  char: string
  color: string
  children?: ReactNode
}) {
  return (
    <span className="source-tag" style={{ '--source-color': color } as CSSProperties}>
      <i className="tick" style={{ background: color }} />
      <span style={{ fontFamily: 'var(--kai)' }}>{char}</span>
      {children}
    </span>
  )
}
