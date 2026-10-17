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
}
