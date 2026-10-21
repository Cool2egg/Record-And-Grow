# 模块六学习记录：文档检索接入（双路检索）

> 写在前面：仅做个人记录，不代表本模块内容

## 1. 简单概要
    主要做了文档检索功能。实现：将问题向量化，然后数据库里匹配
    

## 2. 模块学习收获
-1 检索结果有三种状态（互斥），区别在于 SQL 到底有没有执行：
    （1）没检索：开关关闭、问题为空、库里没有 READY 文档。不调 embedding，SQL 不执行，documentSearched=false、documentsDegraded=false；
    （2）已检索：embedding 成功、SQL 执行了，命中 0 条也算检索过。documentSearched=true；
    （3）降级：有 READY 文档但查询向量化失败（超时/限流/鉴权/上游错误/维度不符等）。documentsDegraded=true 且带 degradeReason，
         documentSearched=false，因为 SQL 根本没执行。
    注意：三种里只有第（3）种是"降级"，前两种都是正常状态。

-2 溯源链路：分块时把 chunkIndex/charStart/charEnd 存进库，检索命中后带入 DocumentHit；
    组装时与 [D1] 编号同源生成 SourceItem，它随 sources 推给前端；前端再用 ref 把来源与正文引用对应起来，
    并用 chunkId 回查原文块。

-3 
-4 
-5 