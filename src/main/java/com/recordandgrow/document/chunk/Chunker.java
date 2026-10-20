package com.recordandgrow.document.chunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按 token 切块，纯函数：不碰 IO、不依赖 Spring。
 *
 * <p>流程：归一化文本 → 按行识别 Markdown 标题 → 段落 → 句末标点 → 超长句按 code point 硬切
 * → 贪心合并到 max-tokens → 相邻块按句回退重叠 → 过滤小段 → 截断到 max-chunks。
 *
 * <p>两条不变式靠「块的内容就是归一化文本的一段子串」保证：
 * <ul>
 *   <li>{@code content == normalized.substring(cp(charStart), cp(charEnd))}；</li>
 *   <li>重叠算进下一块：{@code charStart(k+1) < charEnd(k)}，但 {@code charStart} 严格递增。</li>
 * </ul>
 *
 * <p>标题行不进入任何块：遇到标题先结束当前块，下一块的起点在标题行之后；
 * 这样 content 里不会混进 {@code "# 标题"}，标题只存 heading 列。
 */
public final class Chunker {

    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final int MAX_HEADING_LENGTH = 500;

    private final TokenCounter counter;
    private final ChunkOptions options;

    public Chunker(TokenCounter counter, ChunkOptions options) {
        this.counter = counter;
        this.options = options;
    }

    public ChunkResult chunk(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return new ChunkResult(List.of(), false);
        }
        List<Atom> atoms = splitOversized(atomize(normalized), normalized);
        if (atoms.isEmpty()) {
            return new ChunkResult(List.of(), false);
        }

        List<Chunk> raw = new ArrayList<>();
        int i = 0;
        while (i < atoms.size()) {
            Atom first = atoms.get(i);
            int endChar = first.endChar;
            int endCp = first.endCp;
            int tokens = counter.count(normalized.substring(first.startChar, endChar));

            int j = i + 1;
            while (j < atoms.size()) {
                Atom next = atoms.get(j);
                if (next.breakBefore) {
                    break;
                }
                int candidateTokens = counter.count(normalized.substring(first.startChar, next.endChar));
                if (candidateTokens > options.maxTokens()) {
                    break;
                }
                endChar = next.endChar;
                endCp = next.endCp;
                tokens = candidateTokens;
                j++;
            }

            String content = normalized.substring(first.startChar, endChar);
            raw.add(new Chunk(0, content, first.heading, first.startCp, endCp,
                    endCp - first.startCp, tokens));

            int nextStart = j;
            // 只有后面确实还有「新」句子时才做重叠：
            // 如果下一块只能由重叠部分组成，那会产出一个与上一块尾部重复的块。
            if (options.overlapTokens() > 0 && j - i > 1
                    && j < atoms.size() && !atoms.get(j).breakBefore) {
                int overlapStart = j - 1;
                while (overlapStart - 1 > i
                        && !atoms.get(overlapStart - 1).breakBefore
                        && counter.count(normalized.substring(
                                atoms.get(overlapStart - 1).startChar, endChar)) <= options.overlapTokens()) {
                    overlapStart--;
                }
                if (overlapStart > i
                        && counter.count(normalized.substring(
                                atoms.get(overlapStart).startChar, endChar)) <= options.overlapTokens()) {
                    nextStart = overlapStart;
                }
            }
            i = Math.max(nextStart, i + 1);
        }

        List<Chunk> kept = raw;
        if (raw.size() > 1) {
            List<Chunk> filtered = raw.stream()
                    .filter(chunk -> chunk.tokenCount() >= options.minTokens())
                    .toList();
            // 全部低于 min-tokens 时保留原样，避免整篇文档被过滤成空
            if (!filtered.isEmpty()) {
                kept = filtered;
            }
        }

        boolean truncated = false;
        if (kept.size() > options.maxChunks()) {
            kept = new ArrayList<>(kept.subList(0, options.maxChunks()));
            truncated = true;
        }

        List<Chunk> result = new ArrayList<>(kept.size());
        for (int index = 0; index < kept.size(); index++) {
            Chunk chunk = kept.get(index);
            result.add(new Chunk(index, chunk.content(), chunk.heading(),
                    chunk.charStart(), chunk.charEnd(), chunk.charCount(), chunk.tokenCount()));
        }
        return new ChunkResult(List.copyOf(result), truncated);
    }

    /** 段落 → 句子。标题行只更新面包屑，不产生 atom。 */
    private List<Atom> atomize(String text) {
        List<Atom> atoms = new ArrayList<>();
        Deque<String> headingStack = new ArrayDeque<>();
        String heading = null;

        int length = text.length();
        int lineStartChar = 0;
        int lineStartCp = 0;
        int paragraphStartChar = -1;
        int paragraphStartCp = -1;
        int paragraphEndChar = -1;
        int paragraphEndCp = -1;
        String paragraphHeading = null;
        boolean paragraphBreak = false;
        boolean afterHeading = false;

        while (true) {
            int newline = text.indexOf('\n', lineStartChar);
            int lineEndChar = newline < 0 ? length : newline;
            String line = text.substring(lineStartChar, lineEndChar);
            int lineCpLength = line.codePointCount(0, line.length());

            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                if (paragraphStartChar >= 0) {
                    addSentenceAtoms(atoms, text, paragraphStartChar, paragraphStartCp,
                            paragraphEndChar, paragraphEndCp, paragraphHeading, paragraphBreak);
                    paragraphStartChar = -1;
                }
                int level = matcher.group(1).length();
                String title = matcher.group(2).strip();
                while (headingStack.size() >= level) {
                    headingStack.removeLast();
                }
                headingStack.addLast(title);
                heading = truncateHeading(String.join(" > ", headingStack));
                afterHeading = true;
            } else if (line.isBlank()) {
                if (paragraphStartChar >= 0) {
                    addSentenceAtoms(atoms, text, paragraphStartChar, paragraphStartCp,
                            paragraphEndChar, paragraphEndCp, paragraphHeading, paragraphBreak);
                    paragraphStartChar = -1;
                }
            } else {
                if (paragraphStartChar < 0) {
                    paragraphStartChar = lineStartChar;
                    paragraphStartCp = lineStartCp;
                    paragraphHeading = heading;
                    paragraphBreak = afterHeading;
                    afterHeading = false;
                }
                paragraphEndChar = lineEndChar;
                paragraphEndCp = lineStartCp + lineCpLength;
            }

            if (newline < 0) {
                break;
            }
            lineStartChar = newline + 1;
            lineStartCp += lineCpLength + 1;
        }
        if (paragraphStartChar >= 0) {
            addSentenceAtoms(atoms, text, paragraphStartChar, paragraphStartCp,
                    paragraphEndChar, paragraphEndCp, paragraphHeading, paragraphBreak);
        }
        return atoms;
    }

    private void addSentenceAtoms(List<Atom> atoms, String text,
                                  int startChar, int startCp, int endChar, int endCp,
                                  String heading, boolean breakBefore) {
        if (startChar >= endChar) {
            return;
        }
        String paragraph = text.substring(startChar, endChar);
        int charIndex = 0;
        int cpOffset = 0;
        int sentenceStartChar = startChar;
        int sentenceStartCp = startCp;
        boolean firstAtom = true;

        while (charIndex < paragraph.length()) {
            int codePoint = paragraph.codePointAt(charIndex);
            charIndex += Character.charCount(codePoint);
            cpOffset++;
            if (!isSentenceEnd(codePoint)) {
                continue;
            }
            // 句末标点后紧跟的收尾引号/括号归入本句
            while (charIndex < paragraph.length()) {
                int next = paragraph.codePointAt(charIndex);
                if (!isClosing(next)) {
                    break;
                }
                charIndex += Character.charCount(next);
                cpOffset++;
            }
            atoms.add(new Atom(sentenceStartChar, startChar + charIndex,
                    sentenceStartCp, startCp + cpOffset, heading, firstAtom && breakBefore));
            firstAtom = false;

            // 跳过空白，下一句不再以空格/换行开头；空白不是任何块的开头，
            // 但块跨句合并时它仍在 substring 区间里。
            while (charIndex < paragraph.length()) {
                int next = paragraph.codePointAt(charIndex);
                if (!Character.isWhitespace(next)) {
                    break;
                }
                charIndex += Character.charCount(next);
                cpOffset++;
            }
            sentenceStartChar = startChar + charIndex;
            sentenceStartCp = startCp + cpOffset;
        }

        if (sentenceStartChar < endChar) {
            atoms.add(new Atom(sentenceStartChar, endChar, sentenceStartCp, endCp,
                    heading, firstAtom && breakBefore));
        }
    }

    /** 句末标点；换行不单独算一句，段落边界已经由调用方处理。 */
    private static boolean isSentenceEnd(int codePoint) {
        return codePoint == '。' || codePoint == '！' || codePoint == '？' || codePoint == '；'
                || codePoint == '!' || codePoint == '?' || codePoint == ';';
    }

    /** 句末标点后可能跟的收尾符号，归入当前句。 */
    private static boolean isClosing(int codePoint) {
        return codePoint == '”' || codePoint == '’' || codePoint == '」' || codePoint == '』'
                || codePoint == '）' || codePoint == ')' || codePoint == '】'
                || codePoint == '》'
                || codePoint == '"' || codePoint == '\'';
    }

    /** 单个「句子」超过 max-tokens 时按 code point 硬切，绝不切在代理对中间。 */
    private List<Atom> splitOversized(List<Atom> atoms, String text) {
        List<Atom> result = new ArrayList<>(atoms.size());
        for (Atom atom : atoms) {
            if (counter.count(text.substring(atom.startChar, atom.endChar)) <= options.maxTokens()) {
                result.add(atom);
                continue;
            }
            String atomText = text.substring(atom.startChar, atom.endChar);
            int totalCodePoints = atom.endCp - atom.startCp;
            int offsetCp = 0;
            boolean firstPiece = true;
            while (offsetCp < totalCodePoints) {
                int remaining = totalCodePoints - offsetCp;
                int low = 1;
                int high = remaining;
                int best = 1;
                while (low <= high) {
                    int mid = (low + high) >>> 1;
                    int midChar = atomText.offsetByCodePoints(0, offsetCp + mid);
                    if (counter.count(atomText.substring(
                            atomText.offsetByCodePoints(0, offsetCp), midChar)) <= options.maxTokens()) {
                        best = mid;
                        low = mid + 1;
                    } else {
                        high = mid - 1;
                    }
                }
                int pieceStartChar = atomText.offsetByCodePoints(0, offsetCp);
                int pieceEndChar = atomText.offsetByCodePoints(0, offsetCp + best);
                result.add(new Atom(atom.startChar + pieceStartChar, atom.startChar + pieceEndChar,
                        atom.startCp + offsetCp, atom.startCp + offsetCp + best,
                        atom.heading, firstPiece && atom.breakBefore));
                offsetCp += best;
                firstPiece = false;
            }
        }
        return result;
    }

    private static String truncateHeading(String heading) {
        if (heading.length() <= MAX_HEADING_LENGTH) {
            return heading;
        }
        return heading.substring(0, MAX_HEADING_LENGTH);
    }

    /**
     * 一个句子或标题边界。
     *
     * <p>char 与 cp 两套偏移都存：切 content 用 char（substring 是 O(len)），
     * 落库用 cp（与 PG char_length 一致）。全程避免对整篇文本重复调用
     * {@code offsetByCodePoints(0, cp)}——那是 O(n)，长文档会退化成平方复杂度。
     */
    private record Atom(int startChar, int endChar, int startCp, int endCp,
                        String heading, boolean breakBefore) {
    }
}
