package com.recordandgrow.ai;

/**
 * 对话模型客户端。业务层只依赖本接口。
 */
public interface ChatClient {

    /**
     * 是否具备调用条件（当前实现只检查 apiKey 是否为空）。
     *
     * <p>默认 {@code true}，保证既有实现与测试不需要改动。业务接口在检索前用它
     * 做预检，避免“先查库、再发现没配 key”。
     */
    default boolean isConfigured() {
        return true;
    }

    ChatResponse complete(ChatRequest request);

    /**
     * 建立流式请求。方法正常返回表示上游已返回 HTTP 200 并建立流；
     * 之后的读取错误通过 {@link ChatStream#next()} 抛出 {@link AiException}。
     */
    ChatStream stream(ChatRequest request);
}
