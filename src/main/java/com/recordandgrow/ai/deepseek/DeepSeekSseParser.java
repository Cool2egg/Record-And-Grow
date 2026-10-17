package com.recordandgrow.ai.deepseek;

import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.ChatChunk;
import com.recordandgrow.ai.ChatUsage;
import com.recordandgrow.ai.FinishReason;
import tools.jackson.databind.ObjectMapper;

/**
 * 解析 DeepSeek 的单个 SSE data 载荷（一个 chat.completion.chunk）。
 */
public final class DeepSeekSseParser {

    private final ObjectMapper mapper;

    public DeepSeekSseParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @return 可转发的增量；只含无法识别字段的块返回 null
     */
    public ChatChunk parse(String data) {
        try {
            DeepSeekWire.Chunk chunk = mapper.readValue(data, DeepSeekWire.Chunk.class);
            ChatUsage usage = toUsage(chunk.usage());
            if (chunk.choices() == null || chunk.choices().isEmpty()) {
                return usage == null ? null : new ChatChunk(null, null, usage);
            }
            DeepSeekWire.ChunkChoice choice = chunk.choices().get(0);
            String content = choice.delta() == null ? null : choice.delta().content();
            FinishReason finishReason = FinishReason.fromWire(choice.finishReason());
            if ((content == null || content.isEmpty()) && finishReason == null && usage == null) {
                return null;
            }
            return new ChatChunk(content, finishReason, usage);
        } catch (RuntimeException e) {
            throw new AiException(AiErrorType.BAD_RESPONSE, "无法解析 DeepSeek 流式响应", e);
        }
    }

    private ChatUsage toUsage(DeepSeekWire.Usage usage) {
        if (usage == null) {
            return null;
        }
        if (usage.promptTokens() == null && usage.completionTokens() == null && usage.totalTokens() == null) {
            return null;
        }
        int prompt = usage.promptTokens() == null ? 0 : usage.promptTokens();
        int completion = usage.completionTokens() == null ? 0 : usage.completionTokens();
        int total = usage.totalTokens() == null ? prompt + completion : usage.totalTokens();
        return new ChatUsage(prompt, completion, total);
    }
}
