package com.recordandgrow.ai;

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
