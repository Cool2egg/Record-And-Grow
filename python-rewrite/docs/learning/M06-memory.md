# M06 记忆

- 三态单向流转：PENDING（带 proposedType）→ 用户 confirm → FACT / INFERRED；reject 等价软删。
- 只有 FACT / INFERRED 进检索；`search` 永远不返回 PENDING。
- 待确认候选按 `normalized`（小写 + 压空白）去重，避免每轮对话重复生成。
- 回答后的异步抽取走独立 session，失败只记日志，绝不影响主回答链路。
- 会话摘要与原文分开存：`summary` + `summary_upto_message_id` 水位，历史加载只取水位之后的消息，再按字符预算从新到旧截断。
