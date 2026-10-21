package com.recordandgrow.ai;

import java.time.Duration;
import java.util.List;

/**
 * 文本向量客户端。业务层只依赖本接口。
 */
public interface EmbeddingClient {

    /**
     * 按输入顺序返回向量；实现内部按供应商批量上限自动分批。
     */
    List<float[]> embed(List<String> texts);

    /**
     * 查询侧专用：按次覆盖请求超时与尝试次数。
     *
     * <p>默认委托 {@link #embed(List)}，未覆写的实现与既有测试完全不受影响。
     * 只有查询链路需要比入库更短的超时（首包预算），入库继续走旧方法的默认策略。
     */
    default List<float[]> embed(List<String> texts, Duration timeout, int maxAttempts) {
        return embed(texts);
    }

    /**
     * 向量维度，供模块五建表/校验使用。
     */
    int dimensions();

    /**
     * 是否已配置可用（目前只看 key 是否为空）。
     *
     * <p>给「上传前预检」用：未配置就不要落盘、不要建记录。
     * 默认 true，避免影响其它实现与既有测试；真实客户端按自己的凭据覆写。
     */
    default boolean isConfigured() {
        return true;
    }
}
