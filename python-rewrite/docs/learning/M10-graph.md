# M10 知识图谱

- 两张普通表 `graph_node / graph_edge`，不引 Neo4j；图是派生数据，文档删除级联清理。
- 抽取按文档进行：取前 20 个块、限 6000 字，让模型输出三元组 JSON；单篇失败只记日志，不影响其它文档。
- 1 跳查询：先用问题里的词与节点 `normalized` 做包含匹配，再查这些节点的出入边，按权重降序限量返回。
- 幂等：节点按 `(document_id, normalized)` 复用，边按 `(source,target,relation,document_id)` 去重。
- `enabled=false` 时 `searched=false`，前端状态页与图谱页据此展示「未启用」。
