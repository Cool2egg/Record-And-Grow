# M02 时间线

- 表结构与 Java 版 V1 迁移逐列对齐（`timestamptz`、软删、`start_time` 索引）。
- 对外响应统一把时间换成 UTC 再序列化；单测直接断言 `2026-11-03T07:30:00+00:00`。
- `PUT` 请求体里 `endTime: null` 就是清空，不引入 PATCH 语义。
- 列表按 `start_time` 倒序、同刻按 id 倒序，保证分页稳定。
- 查询参数 `start/end` 用 ISO-8601 解析，失败返回 40000 并给出示例格式。
