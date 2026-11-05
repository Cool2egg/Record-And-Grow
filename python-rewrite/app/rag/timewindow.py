"""时间窗解析：把中文问题里的时间表达解析成半开区间 ``[start, end)``。

与 Java 版 ``TimeWindowParser`` 行为对齐：只取问题里最早出现的时间表达，
同一位置取最长匹配；解析失败回落到默认窗口。
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from enum import Enum
from zoneinfo import ZoneInfo

from app.core.config import settings


class TimeBasis(str, Enum):
    DEFAULT = "DEFAULT"
    ABSOLUTE_DAY = "ABSOLUTE_DAY"
    ABSOLUTE_MONTH = "ABSOLUTE_MONTH"
    ABSOLUTE_YEAR = "ABSOLUTE_YEAR"
    RELATIVE_DAY = "RELATIVE_DAY"
    RELATIVE_WEEK = "RELATIVE_WEEK"
    RELATIVE_MONTH = "RELATIVE_MONTH"
    RELATIVE_RECENT = "RELATIVE_RECENT"


@dataclass(frozen=True)
class TimeWindow:
    start: datetime
    end: datetime
    basis: TimeBasis = TimeBasis.DEFAULT
    clamped: bool = False


_CN_WEEKDAY = {"一": 0, "二": 1, "三": 2, "四": 3, "五": 4, "六": 5, "日": 6, "天": 6}


def _chinese_digit_last(text: str) -> int:
    if not text:
        return 0
    return {
        "一": 1, "二": 2, "三": 3, "四": 4, "五": 5,
        "六": 6, "七": 7, "八": 8, "九": 9,
    }.get(text[-1], 0)


def _parse_int_loose(value: str) -> int:
    if value.isdigit():
        return int(value)
    text = value.replace("两", "二")
    ten = text.find("十")
    if ten < 0:
        return _chinese_digit_last(text)
    tens = 1 if ten == 0 else _chinese_digit_last(text[:ten])
    ones = 0 if ten == len(text) - 1 else _chinese_digit_last(text[ten + 1:])
    return tens * 10 + ones


class _Unit(Enum):
    DAY = "day"
    WEEK = "week"
    MONTH = "month"
    YEAR = "year"


class TimeWindowParser:
    def __init__(self, zone: ZoneInfo | None = None) -> None:
        self.zone = zone or ZoneInfo(settings.chat.timezone)

    # ---- 各时间表达的正则 ----
    _FULL_DATE = re.compile(r"(\d{4})\s*[年/\-]\s*(\d{1,2})\s*[月/\-]\s*(\d{1,2})\s*[日号]?")
    _YEAR_MONTH = re.compile(r"(\d{4})\s*年\s*(\d{1,2})\s*月")
    _RELATIVE_YEAR_MONTH = re.compile(r"(去年|今年|明年)\s*(\d{1,2})\s*月")
    _TWO_WEEKS_AGO_DAY = re.compile(r"(?<![上前])上上(?:个)?(?:周|星期)([一二三四五六日天])")
    _TWO_WEEKS_AGO = re.compile(r"(?<![上前])上上(?:个)?(?:周|星期)(?![一二三四五六日天末])")
    _LAST_WEEK_DAY = re.compile(r"(?<![上前])上(?:个)?(?:周|星期)([一二三四五六日天])")
    _RELATIVE_DAY = re.compile(r"(?<![前大])(大前天|大后天|今天|今日|昨天|昨日|前天|明天|后天)")
    _RELATIVE_WEEK = re.compile(r"(?<![上前下])(本周|这周|本星期|这个星期|上周|上个星期|上星期|下周|下个星期|下星期)")
    _TWO_MONTHS_AGO = re.compile(r"(?<![上前])上上个?月")
    _RELATIVE_MONTH = re.compile(r"(?<![上前下])(本月|这个月|当月|上个月|上月|下个月|下月)")
    _RECENT = re.compile(r"(最近|近)\s*(\d+|[一二两三四五六七八九十]+)\s*(天|日|周|个?星期|个月|月|年)")
    _RECENT_HALF_YEAR = re.compile(r"(最近|近)\s*(半年)")
    _MONTH_DAY = re.compile(r"(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]")
    _MONTH_ONLY = re.compile(r"(\d{1,2})\s*月")
    _YEAR_ONLY = re.compile(r"(\d{4})\s*年")
    _RELATIVE_YEAR = re.compile(r"(今年|去年|明年)")

    def parse(self, raw_question: str | None, now: datetime | None = None) -> TimeWindow:
        now = now or datetime.now(self.zone)
        question = self._normalize(raw_question or "")
        matches: list[tuple[int, int, TimeWindow]] = []

        def collect(pattern: re.Pattern[str], mapper):
            m = pattern.search(question)
            if not m:
                return
            window = mapper(m)
            if window is not None:
                matches.append((m.start(), m.end() - m.start(), window))

        collect(self._FULL_DATE, lambda m: self._full_date(int(m.group(1)), int(m.group(2)), int(m.group(3))))
        collect(self._YEAR_MONTH, lambda m: self._year_month(int(m.group(1)), int(m.group(2)), TimeBasis.ABSOLUTE_MONTH, now))
        collect(self._RELATIVE_YEAR_MONTH, lambda m: self._year_month(now.year + self._year_offset(m.group(1)), int(m.group(2)), TimeBasis.ABSOLUTE_MONTH, now))
        collect(self._TWO_WEEKS_AGO_DAY, lambda m: self._weekday_ago(m.group(1), 2, now))
        collect(self._TWO_WEEKS_AGO, lambda m: self._relative_week(m.group(), now))
        collect(self._LAST_WEEK_DAY, lambda m: self._weekday_ago(m.group(1), 1, now))
        collect(self._RELATIVE_DAY, lambda m: self._relative_day(m.group(1), now))
        collect(self._RELATIVE_WEEK, lambda m: self._relative_week(m.group(1), now))
        collect(self._TWO_MONTHS_AGO, lambda m: self._relative_month(m.group(), now))
        collect(self._RELATIVE_MONTH, lambda m: self._relative_month(m.group(1), now))
        collect(self._RECENT, lambda m: self._recent(_parse_int_loose(m.group(2)), self._unit_of(m.group(3)), now))
        collect(self._RECENT_HALF_YEAR, lambda m: self._recent(6, _Unit.MONTH, now))
        collect(self._MONTH_DAY, lambda m: self._month_day(int(m.group(1)), int(m.group(2)), now))
        collect(self._MONTH_ONLY, lambda m: self._month_only(int(m.group(1)), now))
        collect(self._RELATIVE_YEAR, lambda m: self._relative_year(m.group(1), now))
        collect(self._YEAR_ONLY, lambda m: self._relative_year(m.group(1), now))

        window = min(
            matches,
            key=lambda x: (x[0], -x[1]),
            default=(0, 0, self._default_window(now)),
        )[2]
        return self._clamp(window)

    # ---- 时间表达 ----

    def _full_date(self, year: int, month: int, day: int) -> TimeWindow | None:
        try:
            return self._day_window(date(year, month, day), TimeBasis.ABSOLUTE_DAY)
        except ValueError:
            return None

    def _year_month(self, year: int, month: int, basis: TimeBasis, now: datetime) -> TimeWindow | None:
        if month < 1 or month > 12:
            return None
        if year == now.year and month == now.month:
            return self._month_to_date(now, basis)
        return self._full_month(year, month, basis)

    def _weekday_ago(self, day_name: str, weeks_ago: int, now: datetime) -> TimeWindow:
        day = _CN_WEEKDAY[day_name]
        monday = now.date() - timedelta(days=now.weekday())
        target = monday - timedelta(weeks=weeks_ago) + timedelta(days=day)
        return self._day_window(target, TimeBasis.RELATIVE_WEEK)

    def _relative_day(self, word: str, now: datetime) -> TimeWindow:
        offset = {
            "今天": 0, "今日": 0, "昨天": -1, "昨日": -1, "前天": -2,
            "大前天": -3, "明天": 1, "后天": 2, "大后天": 3,
        }.get(word, 0)
        return self._day_window(now.date() + timedelta(days=offset), TimeBasis.RELATIVE_DAY)

    def _relative_week(self, word: str, now: datetime) -> TimeWindow | None:
        offset = {
            "本周": 0, "这周": 0, "本星期": 0, "这个星期": 0,
            "上周": -1, "上个星期": -1, "上星期": -1,
            "上上周": -2, "上上个周": -2, "上上星期": -2, "上上个星期": -2,
            "下周": 1, "下个星期": 1, "下星期": 1,
        }.get(word)
        if offset is None:
            return None
        monday = now.date() - timedelta(days=now.weekday()) + timedelta(weeks=offset)
        return TimeWindow(
            self._start_of_day(monday), self._start_of_day(monday + timedelta(weeks=1)),
            TimeBasis.RELATIVE_WEEK, False,
        )

    def _relative_month(self, word: str, now: datetime) -> TimeWindow | None:
        if word in ("本月", "这个月", "当月"):
            return self._month_to_date(now, TimeBasis.RELATIVE_MONTH)
        if word in ("上个月", "上月"):
            return self._full_month_offset(now, -1, TimeBasis.RELATIVE_MONTH)
        if word in ("上上个月", "上上月"):
            return self._full_month_offset(now, -2, TimeBasis.RELATIVE_MONTH)
        if word in ("下个月", "下月"):
            return self._full_month_offset(now, 1, TimeBasis.RELATIVE_MONTH)
        return None

    def _recent(self, count: int, unit: _Unit, now: datetime) -> TimeWindow | None:
        if count <= 0 or count > 10000:
            return None
        today = now.date()
        start = {
            _Unit.DAY: today - timedelta(days=count - 1),
            _Unit.WEEK: today - timedelta(weeks=count) + timedelta(days=1),
            _Unit.MONTH: self._add_months(today, -count) + timedelta(days=1),
            _Unit.YEAR: self._add_months(today, -count * 12) + timedelta(days=1),
        }[unit]
        start_time = self._start_of_day(start)
        return TimeWindow(start_time, self._end_at(start_time, now), TimeBasis.RELATIVE_RECENT, False)

    def _month_day(self, month: int, day: int, now: datetime) -> TimeWindow | None:
        if month < 1 or month > 12:
            return None
        try:
            candidate = date(now.year, month, day)
            if candidate > now.date():
                candidate = candidate.replace(year=now.year - 1)
            return self._day_window(candidate, TimeBasis.ABSOLUTE_DAY)
        except ValueError:
            return None

    def _month_only(self, month: int, now: datetime) -> TimeWindow | None:
        if month < 1 or month > 12:
            return None
        if month == now.month:
            return self._month_to_date(now, TimeBasis.ABSOLUTE_MONTH)
        year = now.year if month < now.month else now.year - 1
        return self._full_month(year, month, TimeBasis.ABSOLUTE_MONTH)

    def _relative_year(self, word: str, now: datetime) -> TimeWindow | None:
        if word == "今年":
            start = self._start_of_day(date(now.year, 1, 1))
            return TimeWindow(start, self._end_at(start, now), TimeBasis.ABSOLUTE_YEAR, False)
        if word == "去年":
            return self._full_year(now.year - 1)
        if word == "明年":
            return self._full_year(now.year + 1)
        try:
            year = int(word)
        except ValueError:
            return None
        if year == now.year:
            return self._relative_year("今年", now)
        return self._full_year(year)

    # ---- 区间工具 ----

    def _default_window(self, now: datetime) -> TimeWindow:
        days = max(1, settings.chat.default_window_days)
        start = self._start_of_day(now.date() - timedelta(days=days - 1))
        return TimeWindow(start, self._end_at(start, now), TimeBasis.DEFAULT, False)

    def _day_window(self, day: date, basis: TimeBasis) -> TimeWindow:
        return TimeWindow(self._start_of_day(day), self._start_of_day(day + timedelta(days=1)), basis, False)

    def _full_month(self, year: int, month: int, basis: TimeBasis) -> TimeWindow:
        start = self._start_of_day(date(year, month, 1))
        end_month = self._add_months(date(year, month, 1), 1)
        return TimeWindow(start, self._start_of_day(end_month), basis, False)

    def _full_month_offset(self, now: datetime, offset: int, basis: TimeBasis) -> TimeWindow:
        target = self._add_months(date(now.year, now.month, 1), offset)
        return self._full_month(target.year, target.month, basis)

    def _month_to_date(self, now: datetime, basis: TimeBasis) -> TimeWindow:
        start = self._start_of_day(date(now.year, now.month, 1))
        return TimeWindow(start, self._end_at(start, now), basis, False)

    def _full_year(self, year: int) -> TimeWindow:
        return TimeWindow(
            self._start_of_day(date(year, 1, 1)),
            self._start_of_day(date(year + 1, 1, 1)),
            TimeBasis.ABSOLUTE_YEAR, False,
        )

    def _start_of_day(self, day: date) -> datetime:
        return datetime.combine(day, time.min, tzinfo=self.zone)

    def _end_at(self, start: datetime, now: datetime) -> datetime:
        current = now
        return current if current > start else start + timedelta(seconds=1)

    def _clamp(self, window: TimeWindow) -> TimeWindow:
        max_days = settings.chat.max_window_days
        if max_days <= 0:
            return window
        min_start = window.end - timedelta(days=max_days)
        if window.start < min_start:
            return TimeWindow(min_start, window.end, window.basis, True)
        return window

    @staticmethod
    def _normalize(question: str) -> str:
        table = str.maketrans("０１２３４５６７８９", "0123456789")
        normalized = question.translate(table)
        normalized = re.sub(r"(?<=\d)[ \t　]+(?=[年月日号])", "", normalized)
        normalized = re.sub(r"(?<=[年月日号])[ \t　]+(?=\d)", "", normalized)
        return normalized

    @staticmethod
    def _year_offset(word: str) -> int:
        return {"去年": -1, "明年": 1}.get(word, 0)

    @staticmethod
    def _unit_of(text: str) -> _Unit:
        return {
            "天": _Unit.DAY, "日": _Unit.DAY,
            "周": _Unit.WEEK, "星期": _Unit.WEEK, "个星期": _Unit.WEEK,
            "个月": _Unit.MONTH, "月": _Unit.MONTH,
            "年": _Unit.YEAR,
        }.get(text, _Unit.DAY)

    @staticmethod
    def _add_months(d: date, months: int) -> date:
        month_index = d.month - 1 + months
        year = d.year + month_index // 12
        month = month_index % 12 + 1
        # 处理月末（如 1/31 + 1 个月 → 2/28）
        import calendar
        day = min(d.day, calendar.monthrange(year, month)[1])
        return date(year, month, day)
