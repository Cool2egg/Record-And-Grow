package com.recordandgrow.agent.orchestration;

import com.recordandgrow.ai.sse.SseEvents;
import com.recordandgrow.memory.dto.MemoryResponse;
import com.recordandgrow.retrieval.query.TimeBasis;
import com.recordandgrow.retrieval.query.TimeWindow;
import com.recordandgrow.timeline.dto.TimelineEntryResponse;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 一条进入证据区的材料。编号 {@code T1/D1/M1/G1} 在这里分配，
 * Prompt 与 SSE {@code sources} 都从同一份列表生成，保证一一对应。
 */
public record EvidenceItem(
        String ref,
        String kind,
        Long id,
        String title,
        String text,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        Long chunkId,
        String heading,
        Double score,
        /** 图谱边的出处文档；其余来源为 null。 */
        Long documentId) {

    private static final int EXCERPT_CHARS = 120;

    public SseEvents.SourceItem toSourceItem() {
        return switch (kind) {
            case "timeline" -> new SseEvents.SourceItem(ref, kind, id, startTime, endTime, excerpt());
            case "document" -> new SseEvents.SourceItem(ref, kind, id, null, null, excerpt(),
                    chunkId, title, heading, score);
            // 记忆的 title 存的是 FACT / INFERRED，落到 memoryType；score 是置信度
            case "memory" -> new SseEvents.SourceItem(ref, kind, id, null, null, excerpt(),
                    null, null, null, score, title, null);
            case "graph" -> new SseEvents.SourceItem(ref, kind, id, null, null, excerpt(),
                    null, title, null, null, null, documentId);
            default -> new SseEvents.SourceItem(ref, kind, id, null, null, excerpt());
        };
    }

    private String excerpt() {
        if (text == null) {
            return "";
        }
        return text.length() <= EXCERPT_CHARS ? text : text.substring(0, EXCERPT_CHARS) + "…";
    }

    /** 证据区的一行文本，已去掉换行，防止正文伪造段落。 */
    public String promptLine(ZoneId zone, DateTimeFormatter timeFormat) {
        String body = sanitize(text);
        return switch (kind) {
            case "timeline" -> {
                String time = startTime == null ? "" : startTime.atZoneSameInstant(zone).format(timeFormat);
                String end = endTime == null ? "" : " ~ " + endTime.atZoneSameInstant(zone).format(timeFormat);
                yield "[" + ref + "] " + time + end + "｜" + truncate(body, 500);
            }
            case "document" -> {
                String headingPart = heading == null || heading.isBlank() ? "" : "｜" + sanitize(heading);
                yield "[" + ref + "]《" + sanitize(title) + "》" + headingPart + "｜" + truncate(body, 400);
            }
            case "memory" -> "[" + ref + "] " + truncate(body, 200);
            case "graph" -> "[" + ref + "] " + truncate(body, 200);
            default -> "[" + ref + "] " + truncate(body, 200);
        };
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("\\p{Cntrl}", " ")
                .replaceAll("[\\s\\u3000]+", " ")
                .trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    public static EvidenceItem timeline(String ref, TimelineEntryResponse entry) {
        return new EvidenceItem(ref, "timeline", entry.getId(), null, entry.getContent(),
                entry.getStartTime(), entry.getEndTime(), null, null, null, null);
    }

    public static EvidenceItem document(String ref, com.recordandgrow.retrieval.document.DocumentHit hit) {
        return new EvidenceItem(ref, "document", hit.documentId(), hit.title(), hit.content(),
                null, null, hit.chunkId(), hit.heading(), hit.score(), null);
    }

    public static EvidenceItem memory(String ref, MemoryResponse memory) {
        return new EvidenceItem(ref, "memory", memory.id(), memory.type(), memory.content(),
                null, null, null, null, memory.confidence(), null);
    }

    public static EvidenceItem graph(String ref, com.recordandgrow.graph.GraphHit hit) {
        return new EvidenceItem(ref, "graph", hit.edgeId(), hit.title(),
                hit.source() + " --" + hit.relation() + "--> " + hit.target(),
                null, null, null, null, null, hit.documentId());
    }

    public String sectionName() {
        return switch (kind) {
            case "timeline" -> "时间线记录";
            case "document" -> "文档片段";
            case "memory" -> "长期记忆";
            case "graph" -> "知识图谱";
            default -> "其他";
        };
    }

    public static List<String> sectionOrder() {
        return List.of("timeline", "document", "memory", "graph");
    }

    public static TimeBasis basisOrNull(TimeWindow window) {
        return window == null ? null : window.basis();
    }
}
