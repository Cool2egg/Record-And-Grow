package com.recordandgrow.retrieval.document;

import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.EmbeddingClient;
import com.recordandgrow.ai.config.AiProperties;
import com.recordandgrow.config.ChatProperties;
import com.recordandgrow.document.DocumentChunkMapper;
import com.recordandgrow.document.DocumentMapper;
import com.recordandgrow.document.DocumentSearchRow;
import com.recordandgrow.document.VectorLiterals;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档召回读路径：存在性判断 → 查询向量化 → pgvector 检索 → 阈值/配额/去重。
 *
 * <p>处理顺序（设计稿 8.2，不可调换）：
 * <ol>
 *   <li>开关关闭 → {@code notSearched}；</li>
 *   <li>没有 READY 文档 → {@code notSearched}（不调 embedding，保护模块四回归）；</li>
 *   <li>没有 embedding key / 配置维度不是 1024 → {@code degraded}，SQL 不执行；</li>
 *   <li>查询 embedding（按次 8s / 1 次）→ 失败映射为降级原因；</li>
 *   <li>SQL 取最近 {@code scan-limit} 行，**异常直接上抛**，不伪装成「没命中」；</li>
 *   <li>{@code score = 1 - distance}，用完整 double；{@code topScore} 在阈值过滤前取最大；</li>
 *   <li>阈值过滤 → 完整排序 → 每文档 ≤2 块 → 相邻块去重 → 取前 {@code top-k}。</li>
 * </ol>
 *
 * <p>不做关键词路、不做查询改写、不做重排；这些属于模块八/九。
 */
@Component
public class DocumentRetriever {

    /** {@code document_chunk.embedding} 的列类型；配置写错时提前降级，而不是等 SQL 报错。 */
    static final int EXPECTED_DIMENSIONS = 1024;

    private final ChatProperties chatProperties;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final EmbeddingClient embeddingClient;
    private final String embeddingModel;

    public DocumentRetriever(ChatProperties chatProperties,
                             AiProperties aiProperties,
                             DocumentMapper documentMapper,
                             DocumentChunkMapper chunkMapper,
                             EmbeddingClient embeddingClient) {
        this.chatProperties = chatProperties;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.embeddingClient = embeddingClient;
        this.embeddingModel = aiProperties.getEmbedding().getModel();
    }

    public DocumentRetrievalResult retrieve(String question) {
        ChatProperties.Documents props = chatProperties.getDocuments();
        if (!props.isEnabled()) {
            return DocumentRetrievalResult.notSearched();
        }
        if (question == null || question.isBlank()) {
            return DocumentRetrievalResult.notSearched();
        }
        // 没有可检索的文档：整条路径不执行，连 embedding 都不调
        if (!documentMapper.existsReady()) {
            return DocumentRetrievalResult.notSearched();
        }
        if (!embeddingClient.isConfigured()) {
            return DocumentRetrievalResult.degraded(DocumentDegradeReason.NOT_CONFIGURED);
        }
        if (embeddingClient.dimensions() != EXPECTED_DIMENSIONS) {
            return DocumentRetrievalResult.degraded(DocumentDegradeReason.DIMENSION);
        }

        float[] vector;
        try {
            List<float[]> vectors = embeddingClient.embed(
                    List.of(question.trim()), props.getQueryTimeout(), props.getQueryMaxAttempts());
            if (vectors == null || vectors.size() != 1 || vectors.get(0) == null) {
                return DocumentRetrievalResult.degraded(DocumentDegradeReason.BAD_RESPONSE);
            }
            if (vectors.get(0).length != embeddingClient.dimensions()) {
                return DocumentRetrievalResult.degraded(DocumentDegradeReason.DIMENSION);
            }
            vector = vectors.get(0);
        } catch (AiException e) {
            // 只捕获 AiException：其它 RuntimeException 是 bug，应该 500，不能伪装成降级
            return DocumentRetrievalResult.degraded(reasonFor(e.getType()));
        }

        int scanLimit = Math.max(1, props.getScanLimit());
        List<DocumentSearchRow> rows = chunkMapper.search(
                VectorLiterals.toLiteral(vector), embeddingModel, scanLimit);

        Double topScore = rows.stream()
                .map(DocumentSearchRow::getDistance)
                .filter(distance -> distance != null)
                .map(distance -> 1.0 - distance)
                .max(Double::compareTo)
                .orElse(null);

        List<Candidate> candidates = new ArrayList<>(rows.size());
        for (DocumentSearchRow row : rows) {
            if (row.getDistance() == null) {
                continue;
            }
            double score = 1.0 - row.getDistance();
            if (score >= props.getSimilarityThreshold()) {
                candidates.add(new Candidate(row, score));
            }
        }
        candidates.sort(CANDIDATE_ORDER);

        List<DocumentHit> hits = select(candidates,
                Math.max(1, props.getTopK()),
                Math.max(1, props.getMaxPerDocument()));
        return DocumentRetrievalResult.of(hits, topScore);
    }

    /** 相似度降序；平局按 documentId / chunkIndex / chunkId，保证两次调用顺序一致。 */
    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparingDouble(Candidate::score).reversed()
            .thenComparingLong(candidate -> candidate.row().getDocumentId())
            .thenComparingInt(candidate -> candidate.row().getChunkIndex())
            .thenComparingLong(candidate -> candidate.row().getChunkId());

    /**
     * 每文档最多 {@code maxPerDocument} 块；同文档且 {@code |ΔchunkIndex| == 1} 的块只留先到的高分块。
     *
     * <p>去重后从候选列表顺延补位：被跳过的相邻块不会让位次空着，直到凑够 {@code topK}。
     */
    private static List<DocumentHit> select(List<Candidate> candidates, int topK, int maxPerDocument) {
        Map<Long, List<Integer>> selectedIndexes = new HashMap<>();
        Map<Long, Integer> perDocument = new HashMap<>();
        List<DocumentHit> hits = new ArrayList<>(Math.min(topK, candidates.size()));
        for (Candidate candidate : candidates) {
            if (hits.size() >= topK) {
                break;
            }
            DocumentSearchRow row = candidate.row();
            long documentId = row.getDocumentId();
            int used = perDocument.getOrDefault(documentId, 0);
            if (used >= maxPerDocument) {
                continue;
            }
            List<Integer> indexes = selectedIndexes.computeIfAbsent(documentId, key -> new ArrayList<>(2));
            boolean adjacent = indexes.stream()
                    .anyMatch(index -> Math.abs(index - row.getChunkIndex()) == 1);
            if (adjacent) {
                continue;
            }
            indexes.add(row.getChunkIndex());
            perDocument.put(documentId, used + 1);
            hits.add(new DocumentHit(
                    row.getChunkId(),
                    documentId,
                    row.getTitle(),
                    row.getHeading(),
                    row.getContent(),
                    candidate.score(),
                    row.getCharStart(),
                    row.getCharEnd()));
        }
        return List.copyOf(hits);
    }

    /**
     * 10 种 {@link AiErrorType} 穷举映射到 7 种降级原因。
     *
     * <p>不写 {@code default}：上游新增错误类型时这里编译失败，强制重新评估，
     * 而不是悄悄落进某个兜底分支。
     */
    static DocumentDegradeReason reasonFor(AiErrorType type) {
        return switch (type) {
            case TIMEOUT -> DocumentDegradeReason.TIMEOUT;
            case RATE_LIMITED -> DocumentDegradeReason.RATE_LIMITED;
            case AUTHENTICATION, INSUFFICIENT_BALANCE -> DocumentDegradeReason.AUTH;
            case UPSTREAM_ERROR, NETWORK, INVALID_REQUEST -> DocumentDegradeReason.UPSTREAM;
            // embedding 不会流式中断；防御性映射，保证枚举全覆盖
            case STREAM_INTERRUPTED -> DocumentDegradeReason.UPSTREAM;
            case BAD_RESPONSE -> DocumentDegradeReason.BAD_RESPONSE;
            case NOT_CONFIGURED -> DocumentDegradeReason.NOT_CONFIGURED;
        };
    }

    private record Candidate(DocumentSearchRow row, double score) {
    }
}
