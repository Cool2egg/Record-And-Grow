package com.recordandgrow.document.chunk;

/**
 * 文本归一化。
 *
 * <p>归一化的产物就是 {@code document.normalized_text}，分块的 {@code char_start/char_end}
 * 全部相对于它；所以归一化只能做一次，切块必须吃归一化后的字符串。
 *
 * <p>规则：CRLF/CR → LF、去行尾空白、连续空行压到 2 个换行、整体 strip。
 * <b>不合并行内空格</b>——中文排版里空格可能有意义，动了会改变字符偏移。
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(text.length());
        int lineStart = 0;
        int blankRun = 0;
        while (true) {
            int newline = text.indexOf('\n', lineStart);
            String line = newline < 0 ? text.substring(lineStart) : text.substring(lineStart, newline);
            String stripped = stripTrailing(line);
            if (stripped.isEmpty()) {
                blankRun++;
                // 最多保留一个空行（即连续两个换行）
                if (blankRun <= 1) {
                    out.append('\n');
                }
            } else {
                blankRun = 0;
                out.append(stripped).append('\n');
            }
            if (newline < 0) {
                break;
            }
            lineStart = newline + 1;
        }
        return out.toString().strip();
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0) {
            int cp = line.codePointBefore(end);
            if (!Character.isWhitespace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return line.substring(0, end);
    }
}
