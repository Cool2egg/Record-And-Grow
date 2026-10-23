package com.recordandgrow.agent;

import com.recordandgrow.ai.ChatMessage;
import com.recordandgrow.config.ChatProperties;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DIRECT 路径的组装：不检索、无来源、Prompt 里只有时间与问题。
 *
 * <p>这条路径不产生任何 {@code [Tn]} / {@code [Dn]}，所以 Prompt 必须显式禁止引用编号，
 * 否则模型会凭空编号，前端拿到一个对不上 sources 的引用。
 */
class ContextAssemblerDirectTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-22T10:00:00+08:00");

    private final ContextAssembler assembler = new ContextAssembler(new ChatProperties(), ZONE);

    @Test
    void producesDirectPromptWithoutAnySources() {
        ContextBundle bundle = assembler.assembleDirect("你好", NOW);

        assertEquals(2, bundle.messages().size());
        assertEquals(ChatMessage.Role.SYSTEM, bundle.messages().get(0).role());
        assertEquals(ContextAssembler.SYSTEM_PROMPT_DIRECT, bundle.messages().get(0).content());
        assertTrue(bundle.messages().get(1).content().contains("你好"));
        assertTrue(bundle.messages().get(1).content().contains("2026-10-22 10:00"));
        assertTrue(bundle.messages().get(0).content().contains("不输出任何引用编号"));
    }

    @Test
    void sourcesAndCountsAreEmpty() {
        ContextBundle bundle = assembler.assembleDirect("今天天气怎么样？", NOW);

        assertEquals(List.of(), bundle.sources().items());
        assertEquals(0, bundle.sources().retrieval().totalIncluded());
        assertEquals(0, bundle.sources().retrieval().matched());
        assertEquals(false, bundle.sources().retrieval().documentSearched());
        assertNull(bundle.sources().retrieval().degradeReason());
        assertEquals(0, bundle.retrievedCount());
        assertEquals(0, bundle.includedCount());
        assertEquals(bundle.messages().get(0).content().length()
                + bundle.messages().get(1).content().length(), bundle.promptChars());
    }
}
