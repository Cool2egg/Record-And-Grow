# RecordAndGrow 前端

带边注的记录簿：正文是回答，边注是证据；实心是事实，空心是推断，虚线是还没被确认的猜测。

设计稿见 `../docs/design/module-13-frontend.md`，本文只记运行方式与实现约定。

## 技术栈

- React 19 + TypeScript + Vite 8
- Ant Design 6 + `@ant-design/x` 2（只取通用能力，不使用默认气泡布局）
- TanStack Query（服务端状态）、React Router（路由）、dayjs（本地时区）
- 正文与回答：霞鹜文楷 Screen（`lxgw-wenkai-screen-webfont`，按 unicode-range 分片）
- 标记与颜色：`src/styles/tokens.css`；引用解析：`src/features/chat/AnswerMarkdown.tsx`

## 运行

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173，/api 代理到 127.0.0.1:8080
npm run typecheck  # tsc --noEmit
npm run build      # 产物在 dist/
```

后端先启动：

```bash
mvn spring-boot:run
```

没有 `DEEPSEEK_API_KEY` / `DASHSCOPE_API_KEY` 也能打开所有页面：
对话页会显示「还没配置模型 key」，其它页照常读已有数据。

## 页面与回链

| 路由 | 说明 | 可回链参数 |
|---|---|---|
| `/` | 对话；三栏 = 会话 / 正文 / 边注 | `?c=<conversationId>` |
| `/timeline` | 按天浏览、24 小时尺、快速记一笔 | `?date=YYYY-MM-DD&focus=<timelineId>` |
| `/memory` | 待确认收件箱 + 事实 / 推断 | `?focus=<memoryId>` |
| `/documents` | 上传、状态推进、分块抽屉 | `?open=<documentId>&chunk=<chunkId>` |
| `/graph` | 一跳关系图 | `?entity=<实体>` |
| `/status` | 数据库、模型 key、技能、版本 | — |

对话正文里的 `[T1]` / `[D1]` / `[M1]` / `[G1]` 只在 `sources.items` 里真实存在时才渲染成小片；
找不到的编号原样保留，让模型编造引用这件事可见。

## SSE 约定

`POST /api/chat/stream`，事件顺序是不变式：

```
start   {requestId, conversationId}
step    {seq, tool, query, hits, ok, ms, error}   ← 检索阶段，可多条
sources {items[], retrieval{...}}                  ← 固定在第一帧 delta 之前
delta   {content}
done    {finishReason, usage}
error   {code, errorType, message, partial, requestId}
```

解析器在 `src/features/chat/sse.ts`，额外处理：

- `: ping` 心跳注释（后端每 15 秒一条）；
- `\r\n` 与跨 chunk 切断的中文（`TextDecoder(stream: true)`）；
- 多行 `data:` 拼接；
- 40 秒没有任何字节 → 主动 abort，按「连接断了」处理；
- 未知事件直接忽略，后端加事件不需要改前端。

流状态用 `useReducer` 管（`useChat.ts`），不和服务端查询缓存混在一起。

## 视觉约定

- 圆角统一 4px，组件库的阴影全部压掉；主色只用在主按钮、进行中与选中态。
- 来源色只用于 3px 刻度与短标签：时 `#2C6B67`、文 `#8A6E24`、忆 `#63538A`、图 `#4B6472`。
- 回答里没有引用编号的叙述段落左侧带 2px 半透明苔色线，表示「这是它自己归纳的」。
- 不引入暗色模式；`prefers-reduced-motion` 下关闭呼吸与落笔动画。
- 1440px 与 390px 都要能用：<1100px 边注变右侧抽屉，<760px 会话栏变顶部菜单。

## 同源部署（Docker）

```bash
# 宿主机先启动后端（8080）
docker compose up -d frontend
# 打开 http://localhost:8081
```

`frontend/nginx.conf` 托管 `dist/`，并把 `/api` 与 `/mcp` 反代到
`host.docker.internal:8080`；SSE 已设置 `proxy_buffering off` 和 300 秒读超时。
生产环境若前后端分域，需要在后端加 CORS，并保证反向代理不缓冲 SSE。

## 已知限制

- 前端不做鉴权与多用户；MCP 仍是无鉴权、只建议监听本机。
- 历史消息里的 `sources` 是 JSON 字符串；旧数据的时间是 epoch 秒，`parseSources`
  会归一化成 ISO，新数据由后端直接写 ISO。
- 回答正文的「推断句」用段落级判断（段落里没有引用小片即加左侧竖线），
  不是逐句语义判断。
