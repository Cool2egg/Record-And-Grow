# M05 检索

- 时间线与文档两路**并行**召回（各自独立 session，避免同一 AsyncSession 并发不安全），先 await 时间线再 await 文档，保证同时失败时抛时间线的异常。
- 向量召回：pgvector `<=>` 余弦距离，`score = 1 - distance`；阈值过滤后按 `(-score, documentId, chunkIndex, chunkId)` 排序，保证顺序稳定。
- 相邻块合并成「段落」：同文档且 chunkIndex 相差 1 时拼接，段落才占 topK 与每文档配额；`chunkId` 取锚块。
- 关键词通道优先 `pg_search` BM25（`@@@`）；扩展/索引缺失或 0 命中时回退 **jieba 分词 + OR ILIKE**，用命中词数当分数——不依赖任何扩展，中文也能召回。pg_search 失败包在 SAVEPOINT 里，不污染事务。
- RRF 只看名次不看分数；k 默认 20，适合 20–40 条的候选池。
- 证据闸门是「整批」判定：最高精排分低于阈值就整批丢弃，而不是逐条过滤——避免模型拿到三条弱证据硬答。
- 降级三态（未检索 / 降级 / 正常）由 `DocumentRetrievalResult` 表达，`documentSearched=false` 且 0 条命中要区分开。
