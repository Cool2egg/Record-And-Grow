# 模块十：记忆系统

> 目标：让对话记得住「上次聊到哪」，并把长期稳定的偏好/事实沉淀下来；同时坚持「AI 的推测不能当用户事实」。

## 1. 三层结构

| 层 | 存储 | 进入 Prompt 的方式 |
|---|---|---|
| 最近对话 | `message` 表 | 最近 12 条 / 4000 字，从新到旧截断 |
| 会话摘要 | `conversation.summary` | 水位之前的旧消息压成一段摘要 |
| 长期记忆 | `memory` 表 | Agent 通过 `search_memory` 工具按需取，或手工确认 |

## 2. 三态记忆

```
PENDING ──confirm──► FACT / INFERRED
   │
   └──reject──► 软删
```

- **PENDING**：AI 提炼的候选，不参与回答；
- **FACT**：用户主动录入，或用户确认「确实如此」；
- **INFERRED**：用户确认过的推断，回答时仍要用「可能/倾向于」；
- 确认时用 `proposed_type` 决定落入 FACT 还是 INFERRED；
- 拒绝 = 软删，保留审计线索。

去重：`normalized`（小写、压空白、去结尾标点）唯一索引只约束 PENDING，同一内容不会每轮重复出现。

## 3. 写入路径

### 3.1 回答后异步提炼

```
AgentChatService.afterAnswer
  ├─ ConversationSummarizer.summarizeAsync
  └─ MemoryExtractor.extractAsync
        └─ 一次 LLM 调用（JSON 数组，最多 2 条）
             └─ MemoryService.addCandidate → PENDING
```

提炼 Prompt 只允许：稳定偏好、长期目标、身份/角色、长期习惯、重要关系、明确原则；排除一次性事件、临时情绪、助手自己的推测。

失败语义：没 key / 超时 / JSON 不合法 → 静默跳过，不影响回答；候选超上限（默认 50）→ 丢弃新候选。

### 3.2 会话摘要

- 触发：水位之后消息数 > `summary-threshold`（默认 20）；
- 每次把「最旧的一批」压进摘要，保留最近 `summary-keep`（默认 6）条原文；
- 落库使用带水位条件的 UPDATE：并发时旧摘要不会覆盖新摘要；
- 摘要失败只影响下一轮上下文长度，不影响对话。

## 4. 读取路径

- Agent 工具 `search_memory(query)`：只查 FACT / INFERRED，返回 `[M1] …`；
- 手工录入 `POST /api/memories`：直接 FACT；
- 记忆页 `GET /api/memories?type=`、`GET /api/memories/pending`；
- 确认 `POST /api/memories/{id}/confirm`、拒绝 `.../reject`。

检索用 `normalized LIKE` 子串匹配，不上向量：单用户记忆量很小，一次 embedding 的延迟与故障面不划算。

## 5. 数据模型（V3）

```sql
conversation(id, title, summary, summary_upto_message_id, message_count, ...)
message(id, conversation_id, role, content, sources, token_count, created_at)
memory(id, type, proposed_type, content, normalized, evidence, source,
       confidence, conversation_id, confirmed_at, ...)
```

`message.sources` 存当时 SSE sources 的 JSON 快照，便于回看依据；`memory.evidence` 存证据数组（timeline/document/conversation）。

## 6. 并发与边界

- 摘要与提炼共用 2 个 daemon 线程的小池，不阻塞对话；
- 同一内容并发提炼：靠 `uk_memory_pending_normalized` 兜底，后到的安静退出；
- 单实例假设：没有分布式锁，多实例部署需要给摘要/提炼加租约；
- 用户删除会话是软删，消息保留在库中，但不再可访问。

## 7. 测试

- `MemoryServiceTest`：归一化、手工录入、PENDING 确认、拒绝约束、去重、上限；
- `MemoryExtractorTest`：围栏/噪声 JSON、非法类型、条数上限；
- `MemoryPersistenceIntegrationTest`（`-Pintegration`）：V3 建表、历史加载、确认流转、检索。
