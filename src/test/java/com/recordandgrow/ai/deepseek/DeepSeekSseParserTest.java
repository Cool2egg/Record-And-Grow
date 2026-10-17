package com.recordandgrow.ai.deepseek;

import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.ChatChunk;
import com.recordandgrow.ai.FinishReason;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekSseParserTest {

    private final DeepSeekSseParser parser = new DeepSeekSseParser(new ObjectMapper());

    @Test
    void parsesContentDelta() {
        ChatChunk chunk = parser.parse("""
                {"choices":[{"delta":{"content":"你好"},"finish_reason":null}]}
                """);
        assertThat(chunk.contentDelta()).isEqualTo("你好");
        assertThat(chunk.finishReason()).isNull();
    }

    @Test
    void parsesFinishReasonAndUsage() {
        ChatChunk chunk = parser.parse("""
                {"choices":[{"delta":{},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}}
                """);
        assertThat(chunk.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(chunk.usage().totalTokens()).isEqualTo(8);
    }

    @Test
    void parsesUsageOnlyChunk() {
        ChatChunk chunk = parser.parse("""
                {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}}
                """);
        assertThat(chunk.hasContent()).isFalse();
        assertThat(chunk.usage().totalTokens()).isEqualTo(8);
    }

    @Test
    void ignoresEmptyChunk() {
        assertThat(parser.parse("""
                {"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}
                """)).isNull();
    }

    @Test
    void malformedJsonBecomesBadResponse() {
        assertThatThrownBy(() -> parser.parse("{not-json"))
                .isInstanceOfSatisfying(AiException.class,
                        e -> assertThat(e.getType()).isEqualTo(AiErrorType.BAD_RESPONSE));
    }
}
