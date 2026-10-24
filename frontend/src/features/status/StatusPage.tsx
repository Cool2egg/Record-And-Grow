import { useQuery, useQueryClient } from '@tanstack/react-query'
import { App as AntdApp } from 'antd'
import dayjs from 'dayjs'
import { statusApi } from '../../api'
import { ErrorBlock, PageHeader } from '../../components/PageChrome'

const FRONTEND_VERSION = '0.1.0'

function StatusCell({
  label,
  value,
  ok,
  hint,
}: {
  label: string
  value: string
  ok?: boolean
  hint?: string
}) {
  return (
    <div className="status-cell">
      <div className="label">{label}</div>
      <div className={`status-value ${ok === undefined ? '' : ok ? 'status-ok' : 'status-bad'}`}>
        {value}
        {hint ? <small>{hint}</small> : null}
      </div>
    </div>
  )
}

export function StatusPage() {
  const { message } = AntdApp.useApp()
  const queryClient = useQueryClient()

  const healthQuery = useQuery({
    queryKey: ['health'],
    queryFn: statusApi.health,
    retry: false,
    refetchInterval: 30_000,
  })
  const systemQuery = useQuery({
    queryKey: ['system'],
    queryFn: statusApi.system,
    retry: false,
  })
  const skillsQuery = useQuery({
    queryKey: ['skills'],
    queryFn: statusApi.skills,
    retry: false,
  })

  const system = systemQuery.data
  const health = healthQuery.data
  const healthOk = health?.status === 'UP' && health?.database === 'UP'
  const skills = skillsQuery.data ?? system?.skills ?? []

  const reloadSkills = async () => {
    try {
      const result = await statusApi.reloadSkills()
      message.success(`重新加载了 ${result.loaded} 个技能。`)
      void queryClient.invalidateQueries({ queryKey: ['skills'] })
      void queryClient.invalidateQueries({ queryKey: ['system'] })
    } catch (exception) {
      message.error(exception instanceof Error ? exception.message : '重载失败')
    }
  }

  return (
    <div className="page">
      <PageHeader
        title="状态"
        description="一页纸回答「它真跑起来了吗」。数据库、模型 key、技能、版本，都在这里。"
        actions={
          <button
            type="button"
            className="text-action"
            onClick={() => {
              void healthQuery.refetch()
              void systemQuery.refetch()
              void skillsQuery.refetch()
            }}
          >
            重新探活
          </button>
        }
      />

      <div className="page-body">
        {systemQuery.isError ? (
          <ErrorBlock
            title="后端没有回应系统状态"
            detail={
              systemQuery.error instanceof Error
                ? systemQuery.error.message
                : '检查后端是否启动在 8080。'
            }
            actions={
              <button type="button" className="ghost-btn" onClick={() => void systemQuery.refetch()}>
                重试
              </button>
            }
          />
        ) : (
          <div className="status-grid">
            <StatusCell
              label="服务"
              value={system ? '在线' : '检测中…'}
              ok={system ? true : undefined}
              hint={system ? `Java ${system.javaVersion}` : undefined}
            />
            <StatusCell
              label="数据库"
              value={healthOk ? 'UP' : healthQuery.isError ? '不可用' : '检测中…'}
              ok={healthOk}
              hint={health?.database ? '连接正常' : undefined}
            />
            <StatusCell
              label="pgvector"
              value={health?.vectorExtension ? '已启用' : health ? '未启用' : '检测中…'}
              ok={health ? health.vectorExtension : undefined}
            />
            <StatusCell
              label="对话模型"
              value={system?.aiConfigured ? '已配置' : system ? '未配置' : '检测中…'}
              ok={system ? system.aiConfigured : undefined}
              hint="只看 key 是否为空，不发请求"
            />
            <StatusCell
              label="向量模型"
              value={system?.embeddingConfigured ? '已配置' : system ? '未配置' : '检测中…'}
              ok={system ? system.embeddingConfigured : undefined}
              hint="文档入库与检索需要"
            />
            <StatusCell
              label="Agent 编排"
              value={system?.agentEnabled ? '允许' : '关闭'}
              ok={system?.agentEnabled}
              hint="没有 key 时自动回落固定链路"
            />
            <StatusCell
              label="图谱存储"
              value={system?.graphStore ?? '—'}
              ok={system?.graphEnabled}
              hint={system?.graphEnabled ? '启用' : '关闭'}
            />
            <StatusCell
              label="MCP"
              value={system?.mcpEnabled ? '开启' : '关闭'}
              ok={system?.mcpEnabled}
              hint="/mcp · stateless"
            />
          </div>
        )}

        <div className="status-grid" style={{ marginTop: 16 }}>
          <StatusCell
            label="后端版本"
            value={system?.version ?? '—'}
            hint={system ? dayjs(system.startedAt).format('M月D日 HH:mm') + ' 启动' : undefined}
          />
          <StatusCell label="前端版本" value={FRONTEND_VERSION} hint="Vite + React 19" />
        </div>

        <div className="section-title">
          技能
          <span className="count">{skills.length} 个</span>
          <button type="button" className="text-action" onClick={() => void reloadSkills()}>
            热重载
          </button>
        </div>

        {skillsQuery.isError ? (
          <div className="detail-hint">
            技能列表没加载出来。可能是 SKILLS_ENABLED=false，或者 `skills/` 目录不存在。
          </div>
        ) : skills.length === 0 ? (
          <div className="memory-empty">没有加载到技能。把 Markdown 放进 `skills/`，再点热重载。</div>
        ) : (
          <table className="skill-table">
            <tbody>
              {skills.map((skill) => (
                <tr key={skill.name}>
                  <td className="skill-name">{skill.name}</td>
                  <td>
                    {skill.description || '（没有描述）'}
                    <div style={{ marginTop: 4 }}>
                      {skill.triggers.map((trigger) => (
                        <span className="skill-trigger" key={trigger}>
                          {trigger}
                        </span>
                      ))}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <div className="section-title" style={{ fontSize: 17 }}>
          接口
        </div>
        <div className="kv-list">
          <div>
            <b>健康检查</b> <code>GET /api/health</code>
          </div>
          <div>
            <b>系统状态</b> <code>GET /api/system</code>
          </div>
          <div>
            <b>技能</b> <code>GET /api/skills</code> · <code>POST /api/skills/reload</code>
          </div>
          <div>
            <b>MCP</b> <code>POST /mcp</code>（stateless，无鉴权，仅本机）
          </div>
          <div>
            <b>数据目录</b> <code>./data/documents</code>
          </div>
        </div>
      </div>
    </div>
  )
}
