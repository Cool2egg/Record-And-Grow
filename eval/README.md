# 评测流水线

三段式：**record（打接口）→ score（算指标）→ report（出报告）**。record 最贵（调模型、花钱），
所以录完就落盘；score 与 report 可以反复重跑，改判定口径不用再调接口。

## 快速开始

```bash
# 1. 录制（应用需要在 8080 跑着）
python -m eval record --set eval/data/eval_set_v1.jsonl --base-url http://127.0.0.1:8080 --out reports/runs/v1.jsonl

# 2. 打分（不调接口，秒级）
python -m eval score --run reports/runs/v1.jsonl --out-dir reports/v1

# 3. 报告已经生成在 reports/v1/：report.md / per_sample.csv / _scores.json / rows.jsonl
python -m eval report --out-dir reports/v1   # 只重新渲染，改了模板时用

# 一条命令跑完
python -m eval all --base-url http://127.0.0.1:8080
```

只跑前几条验证链路：`--limit 3`。

## 产物

| 文件 | 用途 |
|---|---|
| `reports/runs/<run>.jsonl` | 录制结果：answer + sources + 耗时，一行一条 |
| `reports/<v>/_scores.json` | 全部指标（overall + 按类别切片 + 失败清单） |
| `reports/<v>/report.md` | 一页纸报告，面试时可贴 |
| `reports/<v>/per_sample.csv` | 逐条明细，Excel 打开 |
| `reports/<v>/rows.jsonl` | 打分后的行，便于二次加工 |

## 指标口径

| 指标 | 定义 | 备注 |
|---|---|---|
| Doc Hit@5 | 前 5 条文档来源里有没有相关证据 | 只看 `type=document` 的来源顺序 |
| Doc Recall@5 | 命中的相关来源 / 期望文档数 | 期望文档数取 `expectedDocTitles` 条数 |
| Doc MRR | 第一条相关来源的倒数排名 | |
| Timeline Hit@5 / Recall / MRR | 同上，只看 `type=timeline` | T 与 D 分开算，混排没有意义 |
| 引用正确率 | 答案里 `[D1]/[T1]` 指向相关来源的比例 | **不需要 judge**，纯集合运算 |
| 行为正确率 | answer 要有相关来源；refuse 要明确说没找到；direct 必须无来源 | |
| 过召率 | 不需要该路却召回了的比例 | |
| 首字/端到端均值 | 毫秒 | 流式才有首字，非流式为 -1 |

两条容易踩的口径：

1. **「该检索」不等于「该有命中」**：无答案的负样本也要求检索，但没有期望证据，
   它的判定归行为指标，不参与召回率——否则会把正确的拒答算成检索缺陷。
2. **按类别切片必看**：overall 会掩盖某一类全错。报告里 `by_category` 就是干这个的。

## 评估集

`data/eval_set_v1.jsonl`，一行一条：

```json
{
  "id": "DOC-01", "category": "DOCUMENT", "difficulty": "easy",
  "question": "daemon.json 里的镜像加速怎么配？",
  "requiresTimeline": false, "requiresDocument": true,
  "expectedDocTitles": ["Docker 实践笔记"],
  "expectedDocPhrases": ["registry-mirrors"],
  "behavior": "answer",
  "groundTruth": "在 daemon.json 里配置 registry-mirrors 字段后重启 docker。"
}
```

证据用**标题 + 正文短语**而不是数据库 id：chunkId 会随分块参数或重建库变化，锚点不会。
新增样本时保持 `id` 唯一、`groundTruth` 写自然语言答案（不要写"应回答……"）。

## RAGAS（可选）

```bash
pip install -r eval/requirements-ragas.txt
python -m eval.ragas_eval   # 见模块 docstring；需要自己传 judge 模型与 key
```

已知限制：run 里的 sources 只有 120 字 excerpt，用它当 context 会低估
`context_recall` / `context_precision`。要精确评估，需要应用侧额外落一份完整上下文。

## 现状

- 评估集 12 条（TL 3 / DOC 4 / MIX 3 / NEG 2），目标 40 条
- 语料已就绪：`eval/seed/` 下 20 篇文档 + 80 条时间线（已在本机库验证，见 `eval/seed/README.md`）
- 下一步：灌文档（要 embedding key）→ `python -m eval record` 出 baseline
