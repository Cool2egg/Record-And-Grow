"""可选的 RAGAS 评分层。

RAGAS 只有 Python 实现，所以它单独一层：读同一次 recorded run，导出成 RAGAS 的
Dataset 再算语义指标。**没有装 ragas 时本模块不参与任何流程**。

已知限制：run 里的 sources 只有 120 字的 excerpt（来自 SSE 协议），
不是完整 chunk 正文。用 excerpt 当 context 会低估 context_recall / context_precision。
要精确评估，需要让应用额外落一份完整上下文，或直接从数据库回捞 chunk。
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from .schema import Observation, Sample


def build_records(samples: dict[str, Sample], observations: list[Observation]) -> list[dict[str, Any]]:
    """导出 (question, answer, contexts, ground_truth) 四元组。"""
    records = []
    for observation in observations:
        sample = samples.get(observation.id)
        if sample is None:
            continue
        records.append(
            {
                "question": sample.question,
                "answer": observation.answer,
                "contexts": [source.get("excerpt") or "" for source in observation.sources],
                "ground_truth": sample.ground_truth,
            }
        )
    return records


def evaluate(records: list[dict[str, Any]], judge_model: str = "deepseek-chat") -> dict[str, float]:
    """跑 RAGAS 五项指标。需要 `pip install -r eval/requirements-ragas.txt`。

    注意 judge 模型与被测模型同源时会高估 faithfulness（同源偏置），
    条件允许时换一家模型做 judge。
    """
    try:
        from datasets import Dataset
        from ragas import evaluate as ragas_evaluate
        from ragas.metrics import answer_correctness, answer_relevancy, context_precision, context_recall, faithfulness
    except ImportError as exc:  # pragma: no cover - 可选依赖
        raise SystemExit(
            "未安装 RAGAS。先执行: pip install -r eval/requirements-ragas.txt"
        ) from exc

    dataset = Dataset.from_list(records)
    result = ragas_evaluate(
        dataset,
        metrics=[faithfulness, answer_relevancy, answer_correctness, context_precision, context_recall],
        llm=judge_model,
    )
    return {key: float(value) for key, value in dict(result).items()}


def write_export(samples: dict[str, Sample], observations: list[Observation], path: str | Path) -> Path:
    import json

    out = Path(path)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        "\n".join(json.dumps(record, ensure_ascii=False) for record in build_records(samples, observations)) + "\n",
        encoding="utf-8",
    )
    return out
