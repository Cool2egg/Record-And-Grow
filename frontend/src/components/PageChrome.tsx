import type { ReactNode } from 'react'

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
}) {
  return (
    <header className="page-head">
      <div>
        <h1 className="page-title">{title}</h1>
        {description ? <p className="page-desc">{description}</p> : null}
      </div>
      {actions ? <div>{actions}</div> : null}
    </header>
  )
}

export function EmptyState({
  title,
  hint,
  action,
}: {
  title: ReactNode
  hint?: ReactNode
  action?: ReactNode
}) {
  return (
    <div className="empty">
      <div className="empty-title">{title}</div>
      {hint ? <div className="empty-hint">{hint}</div> : null}
      {action ? <div style={{ marginTop: 8 }}>{action}</div> : null}
    </div>
  )
}

export function ErrorBlock({
  title,
  detail,
  actions,
}: {
  title: ReactNode
  detail?: ReactNode
  actions?: ReactNode
}) {
  return (
    <div className="error-block" role="alert">
      <div className="error-title">{title}</div>
      {detail ? <div className="error-detail">{detail}</div> : null}
      {actions ? <div className="error-actions">{actions}</div> : null}
    </div>
  )
}
