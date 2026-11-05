# M04 文档入库

- 上传只做校验 + 落盘 + 建记录；解析/切块/向量化放到后台协程，状态流转 `PENDING → PARSING → EMBEDDING → READY/FAILED`。
- 去重靠 `content_sha256` 的部分唯一索引（软删后允许重传）；重复上传返回 200 + `deduplicated=true`。
- 切块按空行分段、Markdown 标题变化处强制断开，保证 `heading` 面包屑与块内容对应；相邻块保留 60 token 重叠。
- 单段超长先按句切，仍超再按字符硬切，避免单块超过模型上下文。
- 对账循环扫描「状态是处理中、但进度心跳超时」的任务重新排队，重启后未完成任务能继续跑。
- 解析失败写入 `error_message`，`reindex` 可手动重试；处理中再次 reindex 返回 40900。
