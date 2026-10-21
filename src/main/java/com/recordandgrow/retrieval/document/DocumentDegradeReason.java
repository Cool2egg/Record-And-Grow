package com.recordandgrow.retrieval.document;

/**
 * 文档检索降级原因。
 *
 * <p>只描述「为什么这次没查成文档」，不携带上游错误正文；对外以枚举名（{@code name()}）
 * 出现在 {@code sources.retrieval.degradeReason}，因此新增值属于协议变更，要谨慎。
 *
 * <p>与 {@code AiErrorType} 的映射见 {@link DocumentRetriever#reasonFor}：10 种 AI 错误
 * 穷举映射到 7 种原因，不写 {@code default}，漏一个就编译报错。
 */
public enum DocumentDegradeReason {

    /** 查询 embedding 超时。 */
    TIMEOUT,
    /** 上游限流（429）。 */
    RATE_LIMITED,
    /** 鉴权失败或余额不足（401 / 402）。 */
    AUTH,
    /** 上游 5xx、网络异常、请求不合法、流式中断。 */
    UPSTREAM,
    /** 上游 2xx 但响应结构不符合预期（条数 / index 异常）。 */
    BAD_RESPONSE,
    /** 没有配置 embedding key：SQL 不执行。 */
    NOT_CONFIGURED,
    /** 向量维度与表结构不符（配置维度 ≠ 1024，或返回向量长度不符）。 */
    DIMENSION
}
