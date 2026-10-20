package com.recordandgrow.retrieval.query;

import com.recordandgrow.config.ChatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把中文问题里的时间表达解析成半开区间 {@link TimeWindow}。
 *
 * <p>当前实现只做规则匹配。只取问题里**最早出现**的时间表达；
 * 同一位置取最长匹配（「上周三」优先于「上周」，「10月17日」优先于「10月」）。
 * 解析失败不报错，回落到默认窗口。
 */
@Component
public class TimeWindowParser {

    private static final Logger log = LoggerFactory.getLogger(TimeWindowParser.class);

    private static final Pattern FULL_DATE = Pattern.compile(
            "(\\d{4})\\s*[年/-]\\s*(\\d{1,2})\\s*[月/-]\\s*(\\d{1,2})\\s*[日号]?");
    private static final Pattern YEAR_MONTH = Pattern.compile("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月");
    private static final Pattern RELATIVE_YEAR_MONTH = Pattern.compile("(去年|今年|明年)\\s*(\\d{1,2})\\s*月");
    private static final Pattern TWO_WEEKS_AGO_DAY = Pattern.compile(
            "(?<![上前])上上(?:个)?(?:周|星期)([一二三四五六日天])");
    private static final Pattern TWO_WEEKS_AGO = Pattern.compile(
            "(?<![上前])上上(?:个)?(?:周|星期)(?![一二三四五六日天末])");
    private static final Pattern LAST_WEEK_DAY = Pattern.compile(
            "(?<![上前])上(?:个)?(?:周|星期)([一二三四五六日天])");
    private static final Pattern RELATIVE_DAY = Pattern.compile(
            "(?<![前大])(大前天|大后天|今天|今日|昨天|昨日|前天|明天|后天)");
    private static final Pattern RELATIVE_WEEK = Pattern.compile(
            "(?<![上前下])(本周|这周|本星期|这个星期|上周|上个星期|上星期|下周|下个星期|下星期)");
    private static final Pattern TWO_MONTHS_AGO = Pattern.compile("(?<![上前])上上个?月");
    private static final Pattern RELATIVE_MONTH = Pattern.compile(
            "(?<![上前下])(本月|这个月|当月|上个月|上月|下个月|下月)");
    private static final Pattern RECENT = Pattern.compile(
            "(最近|近)\\s*(\\d+|[一二两三四五六七八九十]+)\\s*(天|日|周|个?星期|个月|月|年)");
    private static final Pattern RECENT_HALF_YEAR = Pattern.compile("(最近|近)\\s*(半年)");
    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]");
    private static final Pattern MONTH_ONLY = Pattern.compile("(\\d{1,2})\\s*月");
    private static final Pattern YEAR_ONLY = Pattern.compile("(\\d{4})\\s*年");
    private static final Pattern RELATIVE_YEAR = Pattern.compile("(今年|去年|明年)");

    private final ChatProperties properties;
    private final Clock clock;
    private final ZoneId zone;

    public TimeWindowParser(ChatProperties properties, Clock clock, ZoneId zone) {
        this.properties = properties;
        this.clock = clock;
        this.zone = zone;
    }

    public TimeWindow parse(String rawQuestion) {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
        String question = normalize(rawQuestion == null ? "" : rawQuestion);

        List<Match> matches = new ArrayList<>();
        collect(question, FULL_DATE, m -> fullDate(
                parseInt(m.group(1)), parseInt(m.group(2)), parseInt(m.group(3)), now), matches);
        collect(question, YEAR_MONTH, m -> yearMonth(
                parseInt(m.group(1)), parseInt(m.group(2)), TimeBasis.ABSOLUTE_MONTH, now), matches);
        collect(question, RELATIVE_YEAR_MONTH, m -> yearMonth(
                now.getYear() + yearOffset(m.group(1)), parseInt(m.group(2)),
                TimeBasis.ABSOLUTE_MONTH, now), matches);
        collect(question, TWO_WEEKS_AGO_DAY, m -> weekDayAgo(m.group(1), 2, now), matches);
        collect(question, TWO_WEEKS_AGO, m -> relativeWeek(m.group(), now), matches);
        collect(question, LAST_WEEK_DAY, m -> weekDayAgo(m.group(1), 1, now), matches);
        collect(question, RELATIVE_DAY, m -> relativeDay(m.group(1), now), matches);
        collect(question, RELATIVE_WEEK, m -> relativeWeek(m.group(1), now), matches);
        collect(question, TWO_MONTHS_AGO, m -> relativeMonth(m.group(), now), matches);
        collect(question, RELATIVE_MONTH, m -> relativeMonth(m.group(1), now), matches);
        collect(question, RECENT, m -> recent(
                parseIntLoose(m.group(2)), unitOf(m.group(3)), now), matches);
        collect(question, RECENT_HALF_YEAR, m -> recent(6, Unit.MONTH, now), matches);
        collect(question, MONTH_DAY, m -> monthDay(
                parseInt(m.group(1)), parseInt(m.group(2)), now), matches);
        collect(question, MONTH_ONLY, m -> monthOnly(parseInt(m.group(1)), now), matches);
        collect(question, RELATIVE_YEAR, m -> relativeYear(m.group(1), now), matches);
        collect(question, YEAR_ONLY, m -> relativeYear(String.valueOf(parseInt(m.group(1))), now), matches);

        TimeWindow window = matches.stream()
                .min(Comparator.comparingInt(Match::start)
                        .thenComparing(Comparator.comparingInt(Match::length).reversed()))
                .map(Match::window)
                .orElseGet(() -> defaultWindow(now));

        if (window.basis() == TimeBasis.DEFAULT) {
            log.debug("time_parse_failed: questionChars={}", question.length());
        }
        return clamp(window);
    }

    private void collect(String question, Pattern pattern, Function<Matcher, TimeWindow> mapper,
                         List<Match> matches) {
        Matcher matcher = pattern.matcher(question);
        if (!matcher.find()) {
            return;
        }
        TimeWindow window = mapper.apply(matcher);
        if (window != null) {
            matches.add(new Match(matcher.start(), matcher.end() - matcher.start(), window));
        }
    }

    // ---- 各类时间表达 ----

    private TimeWindow fullDate(int year, int month, int day, ZonedDateTime now) {
        try {
            return dayWindow(LocalDate.of(year, month, day), TimeBasis.ABSOLUTE_DAY);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private TimeWindow yearMonth(int year, int month, TimeBasis basis, ZonedDateTime now) {
        if (month < 1 || month > 12) {
            return null;
        }
        YearMonth ym = YearMonth.of(year, month);
        if (year == now.getYear() && month == now.getMonthValue()) {
            return monthToDate(now, basis);
        }
        return fullMonth(ym, basis);
    }

    private TimeWindow weekDayAgo(String dayName, int weeksAgo, ZonedDateTime now) {
        DayOfWeek day = dayOfWeek(dayName);
        LocalDate thisMonday = now.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate target = thisMonday.minusWeeks(weeksAgo).plusDays(day.getValue() - 1L);
        return dayWindow(target, TimeBasis.RELATIVE_WEEK);
    }

    private TimeWindow relativeDay(String word, ZonedDateTime now) {
        int offset = switch (word) {
            case "今天", "今日" -> 0;
            case "昨天", "昨日" -> -1;
            case "前天" -> -2;
            case "大前天" -> -3;
            case "明天" -> 1;
            case "后天" -> 2;
            case "大后天" -> 3;
            default -> 0;
        };
        return dayWindow(now.toLocalDate().plusDays(offset), TimeBasis.RELATIVE_DAY);
    }

    private TimeWindow relativeWeek(String word, ZonedDateTime now) {
        Integer offset = switch (word) {
            case "本周", "这周", "本星期", "这个星期" -> 0;
            case "上周", "上个星期", "上星期" -> -1;
            case "上上周", "上上个周", "上上星期", "上上个星期" -> -2;
            case "下周", "下个星期", "下星期" -> 1;
            default -> null;
        };
        if (offset == null) {
            return null;
        }
        LocalDate monday = now.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .plusWeeks(offset);
        return new TimeWindow(startOfDay(monday), startOfDay(monday.plusWeeks(1)),
                TimeBasis.RELATIVE_WEEK, false);
    }

    private TimeWindow relativeMonth(String word, ZonedDateTime now) {
        return switch (word) {
            case "本月", "这个月", "当月" -> monthToDate(now, TimeBasis.RELATIVE_MONTH);
            case "上个月", "上月" -> fullMonth(YearMonth.from(now).minusMonths(1), TimeBasis.RELATIVE_MONTH);
            case "上上个月", "上上月" -> fullMonth(YearMonth.from(now).minusMonths(2), TimeBasis.RELATIVE_MONTH);
            case "下个月", "下月" -> fullMonth(YearMonth.from(now).plusMonths(1), TimeBasis.RELATIVE_MONTH);
            default -> null;
        };
    }

    private TimeWindow recent(int count, Unit unit, ZonedDateTime now) {
        if (count <= 0 || count > 10_000) {
            return null;
        }
        LocalDate today = now.toLocalDate();
        LocalDate start = switch (unit) {
            case DAY -> today.minusDays(count - 1L);
            case WEEK -> today.minusWeeks(count).plusDays(1);
            case MONTH -> today.minusMonths(count).plusDays(1);
            case YEAR -> today.minusYears(count).plusDays(1);
        };
        OffsetDateTime startTime = startOfDay(start);
        return new TimeWindow(startTime, endAt(startTime, now),
                TimeBasis.RELATIVE_RECENT, false);
    }

    private TimeWindow monthDay(int month, int day, ZonedDateTime now) {
        if (month < 1 || month > 12) {
            return null;
        }
        try {
            LocalDate candidate = LocalDate.of(now.getYear(), month, day);
            // 省略年份时取最近一次不晚于今天的日期
            if (candidate.isAfter(now.toLocalDate())) {
                candidate = candidate.minusYears(1);
            }
            return dayWindow(candidate, TimeBasis.ABSOLUTE_DAY);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private TimeWindow monthOnly(int month, ZonedDateTime now) {
        if (month < 1 || month > 12) {
            return null;
        }
        if (month == now.getMonthValue()) {
            return monthToDate(now, TimeBasis.ABSOLUTE_MONTH);
        }
        int year = month < now.getMonthValue() ? now.getYear() : now.getYear() - 1;
        return fullMonth(YearMonth.of(year, month), TimeBasis.ABSOLUTE_MONTH);
    }

    private TimeWindow relativeYear(String word, ZonedDateTime now) {
        int year;
        if ("今年".equals(word)) {
            year = now.getYear();
            OffsetDateTime start = startOfDay(LocalDate.of(year, 1, 1));
            return new TimeWindow(start, endAt(start, now), TimeBasis.ABSOLUTE_YEAR, false);
        } else if ("去年".equals(word)) {
            year = now.getYear() - 1;
            return fullYear(year);
        } else if ("明年".equals(word)) {
            return fullYear(now.getYear() + 1);
        } else {
            try {
                year = Integer.parseInt(word);
            } catch (NumberFormatException e) {
                return null;
            }
            if (year == now.getYear()) {
                return relativeYear("今年", now);
            }
            return fullYear(year);
        }
    }

    // ---- 区间工具 ----

    private TimeWindow defaultWindow(ZonedDateTime now) {
        int days = Math.max(1, properties.getDefaultWindowDays());
        LocalDate startDate = now.toLocalDate().minusDays(days - 1L);
        OffsetDateTime start = startOfDay(startDate);
        return new TimeWindow(start, endAt(start, now), TimeBasis.DEFAULT, false);
    }

    private TimeWindow dayWindow(LocalDate day, TimeBasis basis) {
        return new TimeWindow(startOfDay(day), startOfDay(day.plusDays(1)), basis, false);
    }

    private TimeWindow fullMonth(YearMonth month, TimeBasis basis) {
        return new TimeWindow(startOfDay(month.atDay(1)),
                startOfDay(month.plusMonths(1).atDay(1)), basis, false);
    }

    private TimeWindow monthToDate(ZonedDateTime now, TimeBasis basis) {
        OffsetDateTime start = startOfDay(YearMonth.from(now).atDay(1));
        return new TimeWindow(start, endAt(start, now), basis, false);
    }

    private TimeWindow fullYear(int year) {
        return new TimeWindow(startOfDay(LocalDate.of(year, 1, 1)),
                startOfDay(LocalDate.of(year + 1, 1, 1)), TimeBasis.ABSOLUTE_YEAR, false);
    }

    private OffsetDateTime startOfDay(LocalDate day) {
        return day.atStartOfDay(zone).toOffsetDateTime();
    }

    /**
     * 「本月/今年/最近 N 天」的 end 取当前时刻；恰好在自然日/自然年起点时
     * end 会等于 start，半开区间不合法。此时把 end 推到 start + 1 秒，
     * 保证窗口合法且仍然只覆盖一个瞬间。
     */
    private static OffsetDateTime endAt(OffsetDateTime start, ZonedDateTime now) {
        OffsetDateTime current = now.toOffsetDateTime();
        return current.isAfter(start) ? current : start.plusSeconds(1);
    }

    private TimeWindow clamp(TimeWindow window) {
        int maxDays = properties.getMaxWindowDays();
        if (maxDays <= 0) {
            return window;
        }
        OffsetDateTime minStart = window.end().minusDays(maxDays);
        if (window.start().isBefore(minStart)) {
            return new TimeWindow(minStart, window.end(), window.basis(), true);
        }
        return window;
    }

    // ---- 解析工具 ----

    /**
     * 全角数字转半角，并删除「数字」与「年/月/日/号」之间的空白。
     * 这样「2020 年 3 月 5 日」和「2020年 3月」都能命中同一条规则。
     */
    private static String normalize(String question) {
        StringBuilder builder = new StringBuilder(question.length());
        for (int i = 0; i < question.length(); i++) {
            char c = question.charAt(i);
            if (c >= '０' && c <= '９') {
                builder.append((char) ('0' + (c - '０')));
            } else {
                builder.append(c);
            }
        }
        String normalized = builder.toString();
        normalized = normalized.replaceAll("(?<=\\d)[ \\t\\u3000]+(?=[年月日号])", "");
        normalized = normalized.replaceAll("(?<=[年月日号])[ \\t\\u3000]+(?=\\d)", "");
        return normalized;
    }

    private static int parseInt(String value) {
        return Integer.parseInt(value);
    }

    /** 支持 1-99 的阿拉伯数字与简单中文数字（一、十二、二十、三十一，两=2）。 */
    private static int parseIntLoose(String value) {
        if (value.matches("\\d+")) {
            return Integer.parseInt(value);
        }
        String text = value.replace('两', '二');
        int ten = text.indexOf('十');
        if (ten < 0) {
            return chineseDigit(text);
        }
        int tens = ten == 0 ? 1 : chineseDigit(text.substring(0, ten));
        int ones = ten == text.length() - 1 ? 0 : chineseDigit(text.substring(ten + 1));
        return tens * 10 + ones;
    }

    private static int chineseDigit(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        return switch (text.charAt(text.length() - 1)) {
            case '一' -> 1;
            case '二' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> 0;
        };
    }

    private static int yearOffset(String word) {
        return switch (word) {
            case "去年" -> -1;
            case "明年" -> 1;
            default -> 0;
        };
    }

    private static Unit unitOf(String text) {
        return switch (text) {
            case "天", "日" -> Unit.DAY;
            case "周", "星期", "个星期" -> Unit.WEEK;
            case "个月", "月" -> Unit.MONTH;
            case "年" -> Unit.YEAR;
            default -> Unit.DAY;
        };
    }

    private static DayOfWeek dayOfWeek(String name) {
        return switch (name) {
            case "一" -> DayOfWeek.MONDAY;
            case "二" -> DayOfWeek.TUESDAY;
            case "三" -> DayOfWeek.WEDNESDAY;
            case "四" -> DayOfWeek.THURSDAY;
            case "五" -> DayOfWeek.FRIDAY;
            case "六" -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
    }

    private enum Unit {DAY, WEEK, MONTH, YEAR}

    private record Match(int start, int length, TimeWindow window) {
    }
}
