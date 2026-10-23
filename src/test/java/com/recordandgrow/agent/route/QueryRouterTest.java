package com.recordandgrow.agent.route;

import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.ChatRequest;
import com.recordandgrow.ai.ChatResponse;
import com.recordandgrow.ai.ChatStream;
import com.recordandgrow.ai.ChatUsage;
import com.recordandgrow.ai.FinishReason;
import com.recordandgrow.config.RoutingProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由的契约：关闭不调用模型、正常解析出四个叶子、任何异常都回退 BOTH 且标记 fallback。
 *
 * <p>用假客户端，不依赖 Spring 与网络。
 */
class QueryRouterTest {

    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    @Test
    void disabledRoutingReturnsBothWithoutCallingModel() {
        FakeChatClient client = new FakeChatClient("{\"route\":\"DOCUMENT\"}", 0);
        QueryRouter router = router(client, Runnable::run, false, 1500);

        RouteDecision decision = router.decide("资料里怎么配镜像加速？");

        assertEquals(QueryRoute.BOTH, decision.route());
        assertFalse(decision.fallback(), "关闭路由是配置选择，不算兜底");
        assertEquals(0, client.calls.get(), "关闭时不应该发生模型调用");
    }

    @Test
    void parsesEachLeaf() {
        for (QueryRoute expected : QueryRoute.values()) {
            FakeChatClient client = new FakeChatClient("{\"route\":\"" + expected.name() + "\",\"confidence\":0.9}", 0);
            assertEquals(expected, router(client, Runnable::run, true, 1500).decide("q").route());
        }
    }

    @Test
    void toleratesCodeFenceAndBareWord() {
        assertEquals(QueryRoute.TIMELINE, decide("```json\n{\"route\":\"TIMELINE\"}\n```"));
        assertEquals(QueryRoute.DOCUMENT, decide("DOCUMENT"));
        assertEquals(QueryRoute.BOTH, decide("  both  "));
    }

    @Test
    void unknownOrMalformedOutputFallsBackToBoth() {
        RouteDecision unknown = router(new FakeChatClient("{\"route\":\"MEMORY\"}", 0),
                Runnable::run, true, 1500).decide("q");
        assertEquals(QueryRoute.BOTH, unknown.route());
        assertTrue(unknown.fallback());
        assertEquals("unknown-route", unknown.reason());

        RouteDecision garbage = router(new FakeChatClient("我觉得应该查文档", 0),
                Runnable::run, true, 1500).decide("q");
        assertEquals(QueryRoute.BOTH, garbage.route());
        assertEquals("bad-json", garbage.reason());

        RouteDecision empty = router(new FakeChatClient("   ", 0), Runnable::run, true, 1500).decide("q");
        assertEquals("empty", empty.reason());
    }

    @Test
    void modelFailureFallsBackToBoth() {
        FakeChatClient client = new FakeChatClient(null, 0);
        client.failure = new IllegalStateException("upstream 500");

        RouteDecision decision = router(client, Runnable::run, true, 1500).decide("q");

        assertEquals(QueryRoute.BOTH, decision.route());
        assertTrue(decision.fallback());
        assertEquals("error", decision.reason());
    }

    @Test
    void notConfiguredFallsBackWithoutCalling() {
        FakeChatClient client = new FakeChatClient("{\"route\":\"DOCUMENT\"}", 0);
        client.configured = false;

        RouteDecision decision = router(client, Runnable::run, true, 1500).decide("q");

        assertEquals("not-configured", decision.reason());
        assertEquals(0, client.calls.get());
    }

    @Test
    void timeoutFallsBackToBoth() {
        pool = Executors.newSingleThreadExecutor();
        FakeChatClient client = new FakeChatClient("{\"route\":\"DOCUMENT\"}", 400);

        long started = System.nanoTime();
        RouteDecision decision = router(client, pool, true, 50).decide("q");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(QueryRoute.BOTH, decision.route());
        assertEquals("timeout", decision.reason());
        assertTrue(elapsedMs < 350, "超时等待必须被 timeout 截断，实际耗时 " + elapsedMs + "ms");
    }

    private QueryRoute decide(String content) {
        return router(new FakeChatClient(content, 0), Runnable::run, true, 1500).decide("q").route();
    }

    private QueryRouter router(ChatClient client, java.util.concurrent.Executor executor,
                               boolean enabled, long timeoutMs) {
        RoutingProperties properties = new RoutingProperties();
        properties.setEnabled(enabled);
        properties.setTimeout(Duration.ofMillis(timeoutMs));
        return new QueryRouter(client, properties, executor);
    }

    /** 可控的假客户端：内容、延迟、异常、是否已配置。 */
    private static final class FakeChatClient implements ChatClient {

        private final String content;
        private final long sleepMs;
        private final AtomicInteger calls = new AtomicInteger();
        private boolean configured = true;
        private RuntimeException failure;

        FakeChatClient(String content, long sleepMs) {
            this.content = content;
            this.sleepMs = sleepMs;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            calls.incrementAndGet();
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure != null) {
                throw failure;
            }
            return new ChatResponse(content, FinishReason.STOP, ChatUsage.empty());
        }

        @Override
        public ChatStream stream(ChatRequest request) {
            throw new UnsupportedOperationException("路由不建流");
        }
    }
}
