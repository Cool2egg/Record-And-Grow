package com.recordandgrow.ai.rerank;

import java.util.List;

/**
 * 精排客户端。与 {@code ChatClient} / {@code EmbeddingClient} 同级：业务只依赖接口，
 * 供应商差异（DashScope / Cohere / 本地 bge）关在实现里。
 *
 * <p>约定：**不抛业务可忽略的异常**。调用方要么得到排序结果，要么拿到
 * {@link com.recordandgrow.ai.AiException} 并自行决定保留原序——精排失败不应该让整次检索失败。
 */
public interface RerankClient {

    /** 是否具备调用条件（key 与地址都配了）。 */
    boolean isConfigured();

    /**
     * @param query     用户问题
     * @param documents 候选正文，顺序即输入顺序
     * @param topN      最多返回多少条
     * @return 按相关度降序的结果；{@code index} 指向 {@code documents} 的下标
     */
    List<RerankScore> rerank(String query, List<String> documents, int topN);
}
