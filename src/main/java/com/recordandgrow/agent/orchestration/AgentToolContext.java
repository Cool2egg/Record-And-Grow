package com.recordandgrow.agent.orchestration;

import com.recordandgrow.ai.sse.SseEvents;
import com.recordandgrow.graph.GraphHit;
import com.recordandgrow.graph.GraphRetrievalResult;
import com.recordandgrow.memory.dto.MemoryResponse;
import com.recordandgrow.retrieval.RetrievalResult;
import com.recordandgrow.retrieval.document.DocumentDegradeReason;
import com.recordandgrow.retrieval.document.DocumentHit;
import com.recordandgrow.retrieval.document.DocumentRetrievalResult;
import com.recordandgrow.retrieval.query.TimeBasis;
import com.recordandgrow.retrieval.query.TimeWindow;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次 Agent 请求的证据账本。
 *
 * <p>工具调用可能并行（AgentScope Toolkit 默认允许），所有写方法都同步；
 * 编号按「首次出现」分配，重复调用同一工具只会更新计数，不会重复编号。
 */
public class AgentToolContext {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Long conversationId;
    private final TimeWindow defaultWindow;
    private final List<String> defaultKeywords;
    private final int maxItems;
    private final int maxTimeline;
    private final int maxDocuments;
    private final int maxMemories;
    private final int maxGraph;
    private final ZoneId zone;

    private final List<EvidenceItem> evidence = new ArrayList<>();
    private final Set<Long> timelineIds = new HashSet<>();
    private final Set<Long> chunkIds = new HashSet<>();
    private final Set<Long> retrievedChunkIds = new HashSet<>();
    private final Set<Long> memoryIds = new HashSet<>();
    private final Set<Long> graphEdgeIds = new HashSet<>();
    private final Set<String> toolCalls = new HashSet<>();
    private final Map<String, Integer> counters = new LinkedHashMap<>();

    private int timelineMatched;
    private int timelineRetrieved;
    private boolean timelineTruncated;
    private TimeWindow timelineWindow;
    private int timelineSeq;
    private int documentSeq;
    private int memorySeq;
    private int graphSeq;
    private boolean documentsSearched;
    private boolean documentsDegraded;
    private DocumentDegradeReason documentsDegradeReason;
    private int documentsRetrieved;
    private Double documentsTopScore;
    private int memorySearched;
    private int graphSearched;
    private String researchNotes;

    public AgentToolContext(Long conversationId, TimeWindow defaultWindow,
                            List<String> defaultKeywords, int maxItems, ZoneId zone) {
        this.conversationId = conversationId;
        this.defaultWindow = defaultWindow;
        this.defaultKeywords = defaultKeywords == null ? List.of() : List.copyOf(defaultKeywords);
        this.maxItems = Math.max(1, maxItems);
        // 按来源分配额，避免一路（通常是时间线）把证据位吃光。
        // 60 条时：时间线 27、文档 18、记忆 6、图谱 6；其余留给未用满的配额。
        this.maxTimeline = Math.max(3, this.maxItems * 45 / 100);
        this.maxDocuments = Math.max(2, this.maxItems * 30 / 100);
        this.maxMemories = Math.max(1, this.maxItems * 10 / 100);
        this.maxGraph = Math.max(1, this.maxItems * 10 / 100);
        this.zone = zone;
    }

    public Long conversationId() {
        return conversationId;
    }

    public TimeWindow defaultWindow() {
        return defaultWindow;
    }

    public List<String> defaultKeywords() {
        return defaultKeywords;
    }

    public ZoneId zone() {
        return zone;
    }

    public synchronized void recordToolCall(String name) {
        toolCalls.add(name);
        counters.merge(name, 1, Integer::sum);
    }

    /** @return 本次新增的证据（用于工具结果回给模型） */
    public synchronized List<EvidenceItem> addTimeline(RetrievalResult result) {
        timelineMatched = (int) Math.max(timelineMatched, result.matched());
        timelineRetrieved = Math.max(timelineRetrieved, result.retrieved());
        timelineTruncated |= result.limitTruncated();
        timelineWindow = result.window();

        List<EvidenceItem> added = new ArrayList<>();
        for (var entry : result.items()) {
            if (timelineSeq >= maxTimeline || evidence.size() >= maxItems) {
                break;
            }
            if (!timelineIds.add(entry.getId())) {
                continue;
            }
            EvidenceItem item = EvidenceItem.timeline("T" + (++timelineSeq), entry);
            evidence.add(item);
            added.add(item);
        }
        return added;
    }

    public synchronized List<EvidenceItem> addDocuments(DocumentRetrievalResult result) {
        documentsSearched |= result.searched();
        documentsDegraded |= result.degraded();
        if (result.degradeReason() != null) {
            documentsDegradeReason = result.degradeReason();
        }
        documentsRetrieved = Math.max(documentsRetrieved, result.hits().size());
        if (result.topScore() != null) {
            documentsTopScore = documentsTopScore == null
                    ? result.topScore() : Math.max(documentsTopScore, result.topScore());
        }

        List<EvidenceItem> added = new ArrayList<>();
        for (DocumentHit hit : result.hits()) {
            if (hit.chunkId() != null) {
                retrievedChunkIds.add(hit.chunkId());
            }
            if (documentSeq >= maxDocuments || evidence.size() >= maxItems) {
                break;
            }
            if (hit.chunkId() != null && !chunkIds.add(hit.chunkId())) {
                continue;
            }
            EvidenceItem item = EvidenceItem.document("D" + (++documentSeq), hit);
            evidence.add(item);
            added.add(item);
        }
        // 检索 Agent 可能用不同关键词查多次：documentsRetrieved 应是去重后的总召回，
        // 不是单次 topK，否则会出现 documentIncluded > documentsRetrieved 的假矛盾。
        if (!retrievedChunkIds.isEmpty()) {
            documentsRetrieved = Math.max(documentsRetrieved, retrievedChunkIds.size());
        }
        return added;
    }

    public synchronized List<EvidenceItem> addMemories(List<MemoryResponse> memories) {
        memorySearched++;
        List<EvidenceItem> added = new ArrayList<>();
        for (MemoryResponse memory : memories) {
            if (memorySeq >= maxMemories || evidence.size() >= maxItems) {
                break;
            }
            if (memory.id() == null || !memoryIds.add(memory.id())) {
                continue;
            }
            EvidenceItem item = EvidenceItem.memory("M" + (++memorySeq), memory);
            evidence.add(item);
            added.add(item);
        }
        return added;
    }

    public synchronized List<EvidenceItem> addGraph(GraphRetrievalResult result) {
        graphSearched++;
        List<EvidenceItem> added = new ArrayList<>();
        for (GraphHit hit : result.hits()) {
            if (graphSeq >= maxGraph || evidence.size() >= maxItems) {
                break;
            }
            if (!graphEdgeIds.add(hit.edgeId())) {
                continue;
            }
            EvidenceItem item = EvidenceItem.graph("G" + (++graphSeq), hit);
            evidence.add(item);
            added.add(item);
        }
        return added;
    }

    /** 来源按固定分节顺序输出：T → D → M → G，前端不依赖工具调用顺序。 */
    private List<EvidenceItem> orderedEvidence() {
        List<EvidenceItem> ordered = new ArrayList<>(evidence.size());
        for (String kind : EvidenceItem.sectionOrder()) {
            evidence.stream().filter(item -> item.kind().equals(kind)).forEach(ordered::add);
        }
        return ordered;
    }

    public synchronized void setResearchNotes(String notes) {
        this.researchNotes = notes;
    }

    public synchronized String researchNotes() {
        return researchNotes;
    }

    public synchronized int evidenceCount() {
        return evidence.size();
    }

    public synchronized List<EvidenceItem> evidence() {
        return List.copyOf(evidence);
    }

    /**
     * 回答 Prompt 的证据区。
     *
     * <p>按「时间线 45% / 文档 35% / 记忆 10% / 图谱 10%」分配字符预算；
     * 某个来源为空时，把它的份额按比例让给其他来源。这样时间线很多时也不会
     * 把文档挤掉——这正是首轮 E2E 暴露的问题。
     */
    public synchronized String evidenceBlock(int maxChars) {
        StringBuilder builder = new StringBuilder(Math.min(maxChars, 8192));
        String[] kinds = {"timeline", "document", "memory", "graph"};
        int[] weights = {45, 35, 10, 10};

        int totalWeight = 0;
        for (int i = 0; i < kinds.length; i++) {
            if (hasKind(kinds[i])) {
                totalWeight += weights[i];
            }
        }
        if (totalWeight == 0) {
            return "";
        }

        boolean truncated = false;
        for (int i = 0; i < kinds.length; i++) {
            String kind = kinds[i];
            int weight = weights[i];
            if (!hasKind(kind)) {
                continue;
            }
            if (builder.length() >= maxChars) {
                truncated = true;
                break;
            }
            List<EvidenceItem> section = evidence.stream()
                    .filter(item -> item.kind().equals(kind))
                    .toList();
            String header = "【" + section.get(0).sectionName() + "】\n";
            if (builder.length() + header.length() > maxChars) {
                truncated = true;
                break;
            }
            builder.append(header);

            int sectionBudget = (int) ((long) maxChars * weight / totalWeight);
            int sectionStart = builder.length();
            for (EvidenceItem item : section) {
                String line = item.promptLine(zone, TIME_FORMAT) + "\n";
                if (builder.length() + line.length() > maxChars) {
                    truncated = true;
                    break;
                }
                if (builder.length() + line.length() > sectionStart + sectionBudget) {
                    truncated = true;
                    break;
                }
                builder.append(line);
            }
            builder.append('\n');
        }
        if (truncated && builder.length() + 12 <= maxChars) {
            builder.append("（证据过多，已截断）\n");
        }
        return builder.toString().trim();
    }

    private boolean hasKind(String kind) {
        return evidence.stream().anyMatch(item -> item.kind().equals(kind));
    }

    public synchronized SseEvents.Sources sources() {
        List<EvidenceItem> ordered = orderedEvidence();
        List<SseEvents.SourceItem> items = ordered.stream()
                .map(EvidenceItem::toSourceItem)
                .toList();
        int timelineIncluded = (int) evidence.stream().filter(i -> i.kind().equals("timeline")).count();
        int documentIncluded = (int) evidence.stream().filter(i -> i.kind().equals("document")).count();
        int memoryIncluded = (int) evidence.stream().filter(i -> i.kind().equals("memory")).count();
        int graphIncluded = (int) evidence.stream().filter(i -> i.kind().equals("graph")).count();

        TimeWindow window = timelineWindow != null ? timelineWindow : defaultWindow;
        SseEvents.RetrievalMeta meta = new SseEvents.RetrievalMeta(
                window.start(),
                window.end(),
                window.basis().name(),
                window.clamped(),
                timelineMatched,
                timelineRetrieved,
                timelineIncluded,
                timelineTruncated,
                false,
                defaultKeywords,
                false,
                documentsSearched,
                documentsRetrieved,
                documentIncluded,
                items.size(),
                documentsDegraded,
                documentsDegradeReason == null ? null : documentsDegradeReason.name(),
                memoryIncluded,
                graphIncluded);
        return new SseEvents.Sources(items, meta);
    }

    public synchronized String debugSummary() {
        return "evidence=" + evidence.size()
                + ", timelineMatched=" + timelineMatched
                + ", docsSearched=" + documentsSearched
                + ", docsDegraded=" + documentsDegraded
                + ", memorySearched=" + memorySearched
                + ", graphSearched=" + graphSearched
                + ", tools=" + counters;
    }

    public synchronized boolean timelineSearched() {
        return timelineRetrieved > 0 || timelineMatched > 0 || timelineWindow != null;
    }

    public synchronized boolean documentsSearched() {
        return documentsSearched;
    }

    public synchronized boolean documentsDegraded() {
        return documentsDegraded;
    }

    public synchronized void markDocumentsDegraded(String reason) {
        documentsDegraded = true;
        documentsSearched = false;
        documentsDegradeReason = parseReason(reason);
    }

    private static DocumentDegradeReason parseReason(String reason) {
        if (reason == null) {
            return null;
        }
        try {
            return DocumentDegradeReason.valueOf(reason);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public synchronized int toolCalls() {
        return counters.values().stream().mapToInt(Integer::intValue).sum();
    }

    public synchronized TimeBasis basis() {
        return (timelineWindow != null ? timelineWindow : defaultWindow).basis();
    }
}
