import { useRef, useState } from 'react'
import { InboxOutlined } from '@ant-design/icons'
import { App as AntdApp } from 'antd'
import { documentsApi } from '../../api'

interface UploadTask {
  id: string
  name: string
  percent: number
  status: 'uploading' | 'done' | 'error'
  error?: string
}

const ALLOWED = /\.(pdf|md|markdown|txt)$/i
const MAX_BYTES = 10 * 1024 * 1024

export function UploadDrop({ onUploaded }: { onUploaded: () => void }) {
  const { message } = AntdApp.useApp()
  const inputRef = useRef<HTMLInputElement | null>(null)
  const [dragging, setDragging] = useState(false)
  const dragDepth = useRef(0)
  const [tasks, setTasks] = useState<UploadTask[]>([])

  const setTask = (id: string, patch: Partial<UploadTask>) => {
    setTasks((previous) =>
      previous.map((task) => (task.id === id ? { ...task, ...patch } : task)),
    )
  }

  const uploadFiles = async (files: File[]) => {
    const valid: File[] = []
    for (const file of files) {
      if (!ALLOWED.test(file.name)) {
        message.error(`${file.name}：只支持 PDF / Markdown / TXT`)
        continue
      }
      if (file.size > MAX_BYTES) {
        message.error(`${file.name}：超过 10MB 上限`)
        continue
      }
      valid.push(file)
    }
    if (valid.length === 0) return

    const ids = valid.map(
      (file) => `${file.name}-${file.size}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    )
    setTasks((previous) => [
      ...previous,
      ...valid.map((file, index) => ({
        id: ids[index],
        name: file.name,
        percent: 0,
        status: 'uploading' as const,
      })),
    ])

    await Promise.all(
      valid.map(async (file, index) => {
        const id = ids[index]
        try {
          const result = await documentsApi.upload(file, (percent) =>
            setTask(id, { percent }),
          )
          setTask(id, { percent: 100, status: 'done' })
          message.success(
            result.deduplicated
              ? `${file.name}：库里已有同内容文档，没有重复入库`
              : `${file.name}：已入库，开始解析`,
          )
          onUploaded()
          window.setTimeout(() => {
            setTasks((previous) => previous.filter((task) => task.id !== id))
          }, 3200)
        } catch (exception) {
          const text = exception instanceof Error ? exception.message : '上传失败'
          setTask(id, { status: 'error', error: text })
          message.error(`${file.name}：${text}`)
        }
      }),
    )
  }

  return (
    <div>
      <div
        className={`dropzone ${dragging ? 'dragging' : ''}`}
        role="button"
        tabIndex={0}
        onClick={() => inputRef.current?.click()}
        onKeyDown={(event) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault()
            inputRef.current?.click()
          }
        }}
        onDragEnter={(event) => {
          event.preventDefault()
          dragDepth.current += 1
          setDragging(true)
        }}
        onDragOver={(event) => event.preventDefault()}
        onDragLeave={(event) => {
          event.preventDefault()
          dragDepth.current = Math.max(0, dragDepth.current - 1)
          if (dragDepth.current === 0) setDragging(false)
        }}
        onDrop={(event) => {
          event.preventDefault()
          dragDepth.current = 0
          setDragging(false)
          void uploadFiles(Array.from(event.dataTransfer.files))
        }}
      >
        <InboxOutlined style={{ fontSize: 20, color: 'var(--moss)' }} />
        <div className="dropzone-title">把资料拖进来</div>
        <div>PDF / Markdown / TXT · 单文件不超过 10MB · 也可以点这里选</div>
        <input
          ref={inputRef}
          type="file"
          multiple
          accept=".pdf,.md,.markdown,.txt"
          style={{ display: 'none' }}
          onChange={(event) => {
            void uploadFiles(Array.from(event.target.files ?? []))
            event.target.value = ''
          }}
        />
      </div>

      {tasks.length > 0 ? (
        <div className="upload-list">
          {tasks.map((task) => (
            <div className="upload-row" key={task.id}>
              <span className="upload-name" title={task.name}>
                {task.name}
              </span>
              <span className="upload-progress">
                <i style={{ width: `${task.percent}%` }} />
              </span>
              <span className="upload-status">
                {task.status === 'uploading'
                  ? `${task.percent}%`
                  : task.status === 'done'
                    ? '已上传'
                    : task.error ?? '失败'}
              </span>
            </div>
          ))}
        </div>
      ) : null}
    </div>
  )
}
