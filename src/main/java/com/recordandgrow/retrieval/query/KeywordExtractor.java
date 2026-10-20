package com.recordandgrow.retrieval.query;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从问题里抽取「高置信」关键词，用于给时间窗内的检索加一层过滤。
 *
 * <p>只抽两种：引号里的短语、连续的 ASCII 词。中文自由文本不做分词——
 * 没有分词器时抽错的概率高于收益，宁可不给 LLM 过滤。
 * 抽不到就返回空列表，检索层按「不过滤」处理。
 */
@Component
public class KeywordExtractor {

    private static final Pattern QUOTED = Pattern.compile("[\"“]([^\"”]{2,40})[\"”]");
    private static final Pattern ASCII_WORD = Pattern.compile("[A-Za-z][A-Za-z0-9+#._-]{1,40}");
    private static final int MAX_KEYWORDS = 5;

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "to", "in", "on", "at", "for", "with", "about",
            "me", "my", "i", "you", "your", "we", "our",
            "what", "which", "who", "how", "when", "where", "why",
            "did", "do", "does", "is", "are", "was", "were", "be", "been",
            "have", "has", "had", "can", "could", "should", "would",
            "this", "that", "these", "those",
            "last", "recent", "recently", "today", "yesterday", "tomorrow",
            "day", "days", "week", "weeks", "month", "months", "year", "years",
            "http", "https", "www", "com");

    public List<String> extract(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();

        Matcher quoted = QUOTED.matcher(question);
        while (quoted.find() && result.size() < MAX_KEYWORDS) {
            add(quoted.group(1), seen, result);
        }

        Matcher ascii = ASCII_WORD.matcher(question);
        while (ascii.find() && result.size() < MAX_KEYWORDS) {
            add(ascii.group(), seen, result);
        }
        return List.copyOf(result);
    }

    private static void add(String candidate, LinkedHashSet<String> seen, List<String> result) {
        String trimmed = candidate.trim();
        if (trimmed.length() < 2) {
            return;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (STOPWORDS.contains(lower)) {
            return;
        }
        // 引号短语已经覆盖的 ASCII 子词不再重复添加（"Spring Boot" 不再拆出 Spring/Boot）；
        // 按「词」比较而不是 substring，避免 Google 把 Go 吞掉（与出现顺序无关）
        if (seen.stream().anyMatch(existing -> containsToken(existing, lower))) {
            return;
        }
        if (seen.add(lower)) {
            result.add(trimmed);
        }
    }

    private static boolean containsToken(String phrase, String token) {
        for (String part : phrase.split("[^\\p{L}\\p{N}+#._-]+")) {
            if (part.equals(token)) {
                return true;
            }
        }
        return false;
    }
}
