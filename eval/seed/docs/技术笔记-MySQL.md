# 技术笔记-MySQL

## 索引
联合索引遵循最左前缀原则，范围查询之后的列无法再用索引。
覆盖索引可以避免回表，explain 的 Extra 里会显示 Using index。

## 分页
深分页用 where id > 上一页最大 id 的方式改写，比 limit offset 快得多。
