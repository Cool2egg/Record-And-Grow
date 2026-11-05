"""时间窗解析。"""
from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

from app.rag.timewindow import TimeBasis, TimeWindowParser

ZONE = ZoneInfo("Asia/Shanghai")
NOW = datetime(2026, 11, 3, 15, 30, tzinfo=ZONE)


def parser() -> TimeWindowParser:
    return TimeWindowParser(zone=ZONE)


def test_absolute_date():
    window = parser().parse("2026年3月5日做了什么", NOW)
    assert window.basis is TimeBasis.ABSOLUTE_DAY
    assert window.start.date().isoformat() == "2026-03-05"
    assert (window.end - window.start).days == 1


def test_relative_day():
    window = parser().parse("昨天跑步了吗", NOW)
    assert window.basis is TimeBasis.RELATIVE_DAY
    assert window.start.date().isoformat() == "2026-11-02"
    assert window.start < window.end


def test_recent_days():
    window = parser().parse("最近7天我都干了什么", NOW)
    assert window.basis is TimeBasis.RELATIVE_RECENT
    # 含今天的 7 个自然日：10-28 00:00 ~ 当前时刻
    assert window.start.date().isoformat() == "2026-10-28"


def test_last_month_full_range():
    window = parser().parse("上个月的学习计划", NOW)
    assert window.basis is TimeBasis.RELATIVE_MONTH
    assert window.start.date().isoformat() == "2026-10-01"
    assert window.end.date().isoformat() == "2026-11-01"


def test_default_window_when_no_time_expression():
    window = parser().parse("我这周状态怎么样", NOW)
    assert window.basis is not TimeBasis.DEFAULT


def test_default_when_unparseable():
    window = parser().parse("你好", NOW)
    assert window.basis is TimeBasis.DEFAULT


def test_clamp_to_max_days():
    parser_ = parser()
    # 2020 全年 366 天，超过 365 天上限 → 截断
    window = parser_.parse("2020年", NOW)
    assert window.clamped is True
    assert (window.end - window.start).days <= 365
