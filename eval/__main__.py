"""命令行入口：python -m eval <record|score|report|all>。"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from . import report as report_mod
from .metrics import aggregate, failures, score
from .runner import record_all
from .schema import load_observations, load_samples

DEFAULT_SET = "eval/data/eval_set_v1.jsonl"
DEFAULT_RUN = "reports/runs/v1.jsonl"
DEFAULT_OUT = "reports/v1"


def _cmd_record(args: argparse.Namespace) -> int:
    samples = load_samples(args.set)
    path = record_all(samples, args.base_url, args.out, timeout=args.timeout, limit=args.limit)
    print(f"已录制 {path}")
    return 0


def _cmd_score(args: argparse.Namespace) -> int:
    samples = {sample.id: sample for sample in load_samples(args.set)}
    observations = load_observations(args.run)
    rows = []
    missing = []
    for observation in observations:
        sample = samples.get(observation.id)
        if sample is None:
            missing.append(observation.id)
            continue
        rows.append(score(sample, observation))
    if missing:
        print(f"警告：{len(missing)} 条运行记录在评估集里找不到对应样本: {missing[:5]}", file=sys.stderr)

    summary = aggregate(rows)
    failed = failures(rows)
    out_dir = Path(args.out_dir)
    report_mod.write_json({"aggregate": summary, "failures": failed}, out_dir / "_scores.json")
    report_mod.write_csv(rows, out_dir / "per_sample.csv")
    report_mod.write_markdown(summary, failed, out_dir / "report.md")
    (out_dir / "rows.jsonl").write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8"
    )
    print(f"已评分 {len(rows)} 条，报告在 {out_dir}")
    return 0


def _cmd_report(args: argparse.Namespace) -> int:
    payload = json.loads((Path(args.out_dir) / "_scores.json").read_text(encoding="utf-8"))
    report_mod.write_markdown(payload["aggregate"], payload.get("failures", []),
                              Path(args.out_dir) / "report.md")
    print(f"已重新生成 {Path(args.out_dir) / 'report.md'}")
    return 0


def _cmd_all(args: argparse.Namespace) -> int:
    samples = load_samples(args.set)
    run_path = record_all(samples, args.base_url, args.run, timeout=args.timeout, limit=args.limit)
    args.run = str(run_path)
    return _cmd_score(args)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="eval", description="RecordAndGrow 评测流水线")
    sub = parser.add_subparsers(dest="command", required=True)

    record = sub.add_parser("record", help="打接口并录制 runs/*.jsonl")
    record.add_argument("--set", default=DEFAULT_SET)
    record.add_argument("--base-url", default="http://127.0.0.1:8080")
    record.add_argument("--out", default=DEFAULT_RUN)
    record.add_argument("--timeout", type=float, default=120.0)
    record.add_argument("--limit", type=int, default=None)
    record.set_defaults(func=_cmd_record)

    score = sub.add_parser("score", help="从 runs 计算指标（不调接口）")
    score.add_argument("--set", default=DEFAULT_SET)
    score.add_argument("--run", default=DEFAULT_RUN)
    score.add_argument("--out-dir", default=DEFAULT_OUT)
    score.set_defaults(func=_cmd_score)

    report = sub.add_parser("report", help="用已有 _scores.json 重新渲染报告")
    report.add_argument("--out-dir", default=DEFAULT_OUT)
    report.set_defaults(func=_cmd_report)

    every = sub.add_parser("all", help="录制 + 评分 + 报告")
    every.add_argument("--set", default=DEFAULT_SET)
    every.add_argument("--base-url", default="http://127.0.0.1:8080")
    every.add_argument("--run", default=DEFAULT_RUN)
    every.add_argument("--out-dir", default=DEFAULT_OUT)
    every.add_argument("--timeout", type=float, default=120.0)
    every.add_argument("--limit", type=int, default=None)
    every.set_defaults(func=_cmd_all)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
