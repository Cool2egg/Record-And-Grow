package com.recordandgrow.controller;

import com.recordandgrow.graph.GraphHit;
import com.recordandgrow.graph.GraphIndexer;
import com.recordandgrow.graph.GraphRetrievalResult;
import com.recordandgrow.graph.GraphRetriever;
import com.recordandgrow.infra.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识图谱接口：检索、手工重建、规模统计。
 */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final GraphRetriever retriever;
    private final GraphIndexer indexer;

    public GraphController(GraphRetriever retriever, GraphIndexer indexer) {
        this.retriever = retriever;
        this.indexer = indexer;
    }

    @GetMapping("/search")
    public ApiResponse<GraphSearchResponse> search(@RequestParam("q") String question,
                                                   @RequestParam(required = false) Integer limit) {
        GraphRetrievalResult result = retriever.retrieve(question);
        List<GraphHit> hits = result.hits();
        if (limit != null && limit > 0 && hits.size() > limit) {
            hits = hits.subList(0, limit);
        }
        return ApiResponse.success(new GraphSearchResponse(
                hits, result.searched(), result.degraded(), result.degradeReason()));
    }

    @PostMapping("/reindex")
    public ApiResponse<ReindexResponse> reindex(
            @RequestParam(defaultValue = "5") int limit) {
        int indexed = indexer.reindex(limit);
        return ApiResponse.success(new ReindexResponse(indexed));
    }

    @GetMapping("/stats")
    public ApiResponse<GraphIndexer.GraphStats> stats() {
        return ApiResponse.success(indexer.stats());
    }

    public record GraphSearchResponse(List<GraphHit> hits, boolean searched,
                                      boolean degraded, String degradeReason) {
    }

    public record ReindexResponse(int indexed) {
    }
}
