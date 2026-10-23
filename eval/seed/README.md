# 评测语料

两批数据，都**固定主键**，重建库后不变——评估集与日志都依赖这一点。

## 1. 时间线（80 条）

```powershell
docker cp eval/seed/timeline.sql recordandgrow-postgres:/tmp/timeline.sql
docker exec recordandgrow-postgres psql -U recordandgrow -d recordandgrow -v ON_ERROR_STOP=1 -f /tmp/timeline.sql
```

- `1001–1040` 手写，含评估锚点（跑步 / 健身深蹲 / 项目联调 / 睡眠 / 书）
- `1041–1080` 由 `generate_series` 生成，内容刻意不含锚点，用来做干扰项
- 重复执行安全（`ON CONFLICT (id) DO NOTHING`）

**时区**：`start_time` 是 `timestamptz`，库里按 UTC 存。用手写 SQL 校验窗口时要写
`timestamptz '2026-10-14 00:00+08'`，直接写 `'2026-10-14'` 会按服务器时区解释，
东亚凌晨的记录会被漏掉——这正是评估里 `TL-01` 要抓的那类 bug。

## 2. 文档（20 篇）

`docs/` 下 20 个 markdown，文件名就是标题（入库时 `title` 取自文件名，评估集按标题匹配）。

走真实入库路径（需要 `DASHSCOPE_API_KEY`，要走解析 / 分块 / 向量化）：

```powershell
Get-ChildItem eval/seed/docs/*.md | ForEach-Object {
    curl.exe -s -X POST -F "file=@$($_.FullName)" http://127.0.0.1:8080/api/documents
}
```

入库是异步的（PENDING → PARSING → EMBEDDING → READY），传完用
`GET /api/documents` 看状态，全 READY 再录 baseline。

## 3. 锚点对照

评估集里的 DOC/MIX 样本靠这些标题命中，改名会让对应样本直接 0 分：

| 评估样本 | 标题 | 关键短语 |
|---|---|---|
| DOC-01 | Docker 实践笔记 | registry-mirrors |
| DOC-02 | 合同模板 | 违约金、百分之二十 |
| DOC-03 | 学习方法笔记 | 番茄、25 分钟 |
| DOC-04 | 健身计划 | 热身、深蹲 |
| MIX-01 | 健身计划 | 有氧、跑 |
| MIX-02 | 健康笔记 | 睡眠、作息 |
| MIX-03 | 读书笔记 | 评价、4 分 |

## 4. 为什么要 20 篇而不是 2 篇

向量检索的失败模式（近义混淆、长文档切碎、精确数字召不回）只有语料量上来才会暴露。
20 篇里有 6 篇是刻意做成"互相相似"的：3 篇技术笔记、2 篇旅行计划、2 篇学习类，
用来考验检索能不能区分"MySQL 索引"和"Redis 缓存"这种同域不同题的问题。
