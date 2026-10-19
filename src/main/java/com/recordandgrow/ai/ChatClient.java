package com.recordandgrow.ai;

/**
 * 对话模型客户端。业务层只依赖本接口。
 */
public interface ChatClient {

    ChatResponse complete(ChatRequest request);

    /**
     * 建立流式请求。方法正常返回表示上游已返回 HTTP 200 并建立流；
     * 之后的读取错误通过 {@link ChatStream#next()} 抛出 {@link AiException}。
     */
    ChatStream stream(ChatRequest request);
}
