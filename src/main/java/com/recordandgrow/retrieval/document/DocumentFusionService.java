package com.recordandgrow.retrieval.document;

import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.rerank.RerankClient;
import com.recordandgrow.ai.rerank.RerankScore;
import com.recordandgrow.config.ChatProperties;
import com.recordandgrow.config.FusionProperties;
import com.recordandgrow.config.RerankProperties;
import com.recordandgrow.document.keyword.KeywordHit;
import com.recordandgrow.document.keyword.KeywordSearcher;
import com.recordandgrow.document.keyword.KeywordStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 文档侧检索入口：向量单路，或向量 + 关键词融合。
 *
 * <p>开关关着（默认）时**原样转发** {@link DocumentRetriever} 的结果，与模块六逐字段一致；
 * 打开后：两路各自召回 → RRF 融合 → 每文档配额/相邻块去重 → topK。
 *
 * <p>融合只在**同源**（都是文档块）之间做。时间线不进这里：它和文档是两类证据，
 * 混在一个排名里只会互相挤占名额。
 *
 * <p>关键词通道失败**不降级整条文档检索**，只是这次少一路召回，WARN 记一条。
 * 理由：ES 是可选组件，它挂掉时向量路仍然可用；把它标成 {@code degraded} 会让
 * "文档检索不可用"这句话变成谎言，而且三态语义会自相矛盾（searched=true 又 degraded=true）。
 */
@Component
public class DocumentFusionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentFusionService.class);

    private final DocumentRetriever vectorRetriever;
    private final ObjectProvider<KeywordSearcher> keywordSearcher;
    private final ObjectProvider<RerankClient> rerankClient;
    private final FusionProperties fusionProperties;
    private final RerankProperties rerankProperties;
    private final ChatProperties chatProperties;

    public DocumentFusionService(DocumentRetriever vectorRetriever,
                                 ObjectProvider<KeywordSearcher> keywordSearcher,
                                 ObjectProvider<RerankClient> rerankClient,
                                 FusionProperties fusionProperties,
                                 RerankProperties rerankProperties,
                                 ChatProperties chatProperties) {
        this.vectorRetriever = vectorRetriever;
        this.keywordSearcher = keywordSearcher;
        this.rerankClient = rerankClient;
        this.fusionProperties = fusionProperties;
        this.rerankProperties = rerankProperties;
        this.chatProperties = chatProperties;
    }

    public DocumentRetrievalResult retrieve(String question) {
        DocumentRetrievalResult vector = vectorRetriever.retrieve(question);
        if (!fusionProperties.isEnabled()) {
            return vector;
        }
        KeywordSearcher searcher = keywordSearcher.getIfAvailable();
        if (searcher == null) {
            return vector;
        }
        // 向量侧整体降级时不做融合：让降级原因原样可见，别用关键词命中把它盖住
        if (vector.degraded()) {
            return vector;
        }

        List<DocumentHit> keywordHits;
        try {
            keywordHits = searcher.search(question).stream().map(DocumentFusionService::toHit).toList();
        } catch (KeywordStoreException e) {
            log.warn("关键词通道失败，本次只用向量: questionChars={}, error={}",
                    question == null ? 0 : question.length(), e.getMessage());
            return vector;
        }
        if (keywordHits.isEmpty()) {
            return vector;
        }

        List<DocumentHit> fused = RrfFusion.fuse(List.of(vector.hits(), keywordHits),
                fusionProperties.getRrfK());
        List<DocumentHit> candidates = fused.stream()
                .limit(Math.max(1, fusionProperties.getCandidateLimit()))
                .toList();
        candidates = applyRerank(question, candidates);

        ChatProperties.Documents props = chatProperties.getDocuments();
        List<DocumentHit> selected = DocumentSelection.select(candidates,
                Math.max(1, props.getTopK()),
                Math.max(1, props.getMaxPerDocument()));
        log.debug("文档融合: vectorHits={}, keywordHits={}, candidates={}, included={}",
                vector.hits().size(), keywordHits.size(), candidates.size(), selected.size());
        return DocumentRetrievalResult.of(selected, vector.topScore(),
                vector.queryEmbeddingMs(), vector.documentSqlMs());
    }

    /**
     * 精排 + 证据闸门。三件事按顺序：调用失败保留原序、闸门按整批最高分判、结果按精排分重排。
     *
     * <p>闸门丢弃时返回空列表，调用方据此输出"未找到"，而不是拿三条弱证据硬答。
     */
    private List<DocumentHit> applyRerank(String question, List<DocumentHit> candidates) {
        if (!rerankProperties.isEnabled() || candidates.isEmpty()) {
            return candidates;
        }
        RerankClient client = rerankClient.getIfAvailable();
        if (client == null || !client.isConfigured()) {
            return candidates;
        }
        try {
            List<String> documents = candidates.stream()
                    .map(hit -> hit.content() == null ? "" : hit.content())
                    .toList();
            List<RerankScore> scores = client.rerank(question, documents, candidates.size());
            if (scores.isEmpty()) {
                return candidates;
            }
            List<Double> values = scores.stream().map(RerankScore::score).toList();
            if (!EvidenceGate.keep(values, rerankProperties.getMinScore())) {
                double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
                log.info("证据闸门拦截: maxRerankScore={}, threshold={}", max, rerankProperties.getMinScore());
                return List.of();
            }
            return scores.stream()
                    .filter(score -> score.index() >= 0 && score.index() < candidates.size())
                    .sorted(Comparator.comparingDouble(RerankScore::score).reversed())
                    .map(score -> withScore(candidates.get(score.index()), score.score()))
                    .toList();
        } catch (AiException e) {
            log.warn("精排失败，保留 RRF 原序: type={}, message={}", e.getType(), e.getMessage());
            return candidates;
        }
    }

    private static DocumentHit withScore(DocumentHit hit, double score) {
        return new DocumentHit(hit.chunkId(), hit.documentId(), hit.title(), hit.heading(),
                hit.content(), score, hit.charStart(), hit.charEnd(), hit.chunkIndex());
    }

    /** 关键词命中转成统一的块结构；BM25 分只用于排序，不参与跨通道比较。 */
    static DocumentHit toHit(KeywordHit hit) {
        return new DocumentHit(
                hit.doc().chunkId(),
                hit.doc().documentId(),
                hit.doc().title(),
                hit.doc().heading(),
                hit.doc().content(),
                hit.score(),
                null,
                null,
                hit.doc().chunkIndex());
    }
}
