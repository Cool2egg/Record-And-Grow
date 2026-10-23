package com.recordandgrow.retrieval;

import com.recordandgrow.agent.route.QueryRoute;
import com.recordandgrow.retrieval.document.DocumentFusionService;
import com.recordandgrow.retrieval.document.DocumentRetrievalResult;
import com.recordandgrow.retrieval.query.TimeWindow;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 检索编排：时间线与文档两路**并行**召回，结果与耗时打包成 {@link RetrievalBundle}。
 *
 * <p>并行的收益只在延迟：两路互不依赖，串行时总耗时约等于两者之和。
 *
 * <p>**失败语义与串行实现完全一致**，这是本类唯一需要小心的地方：
 * <ul>
 *   <li>先 join 时间线、再 join 文档，因此两路同时失败时抛出的是时间线的异常（与串行顺序一致）；</li>
 *   <li>异常原样解包，不新增错误类型，{@code GlobalExceptionHandler} 的映射不受影响；</li>
 *   <li>文档侧的降级三态仍由 {@link DocumentFusionService}（内部是
 *       {@code DocumentRetriever}）自己保证，这里不做二次包装。</li>
 * </ul>
 *
 * <p>MDC（{@code requestId}）显式传播到工作线程；不传播的话并行后日志会丢一半链路标识。
 *
 * <p>本类不做通道超时与单通道降级——那需要先定义"超时算什么错误"，属于模块九融合层的职责。
 */
@Component
public class RetrievalEngine {

    private final TimelineRetriever timelineRetriever;
    private final DocumentFusionService documentRetriever;
    private final Executor executor;

    public RetrievalEngine(TimelineRetriever timelineRetriever,
                           DocumentFusionService documentRetriever,
                           @Qualifier("retrievalExecutor") Executor executor) {
        this.timelineRetriever = timelineRetriever;
        this.documentRetriever = documentRetriever;
        this.executor = executor;
    }

    public RetrievalBundle retrieve(String question, TimeWindow window, List<String> keywords) {
        return retrieve(question, window, keywords, QueryRoute.BOTH);
    }

    /**
     * 按路由决定查哪几路。被跳过的路返回"未检索"占位，不产生耗时，也不产生异常。
     *
     * <p>顺序仍然是先 join 时间线：路由只影响"要不要发"，不影响"谁先失败"。
     */
    public RetrievalBundle retrieve(String question, TimeWindow window, List<String> keywords,
                                    QueryRoute route) {
        if (route == QueryRoute.DIRECT) {
            return new RetrievalBundle(RetrievalResult.empty(window, keywords),
                    DocumentRetrievalResult.notSearched(), 0, 0);
        }

        Map<String, String> mdc = MDC.getCopyOfContextMap();

        CompletableFuture<Timed<RetrievalResult>> timeline = route.retrievesTimeline()
                ? supplyAsync(() -> timed(() -> timelineRetriever.retrieve(window, keywords)), mdc)
                : completed(RetrievalResult.empty(window, keywords));
        CompletableFuture<Timed<DocumentRetrievalResult>> documents = route.retrievesDocuments()
                ? supplyAsync(() -> timed(() -> documentRetriever.retrieve(question)), mdc)
                : completed(DocumentRetrievalResult.notSearched());

        Timed<RetrievalResult> timelineResult = join(timeline);
        Timed<DocumentRetrievalResult> documentResult = join(documents);

        return new RetrievalBundle(
                timelineResult.value(),
                documentResult.value(),
                timelineResult.elapsedMs(),
                documentResult.elapsedMs());
    }

    private static <T> CompletableFuture<Timed<T>> completed(T value) {
        return CompletableFuture.completedFuture(new Timed<>(value, 0));
    }

    private <T> CompletableFuture<Timed<T>> supplyAsync(Supplier<Timed<T>> supplier, Map<String, String> mdc) {
        return CompletableFuture.supplyAsync(() -> withMdc(mdc, supplier), executor);
    }

    /** 记录单路耗时；异常路径不需要耗时，直接抛出。 */
    private static <T> Timed<T> timed(Supplier<T> action) {
        long started = System.nanoTime();
        T value = action.get();
        return new Timed<>(value, (System.nanoTime() - started) / 1_000_000);
    }

    /**
     * 把调用线程的 MDC 带进工作线程，执行完还原。
     *
     * <p>用 {@code setContextMap} 而不是逐个 key 复制，保证嵌套 key（如将来的 traceId）也能带走。
     */
    private static <T> T withMdc(Map<String, String> context, Supplier<T> action) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
        try {
            return action.get();
        } finally {
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }

    /**
     * {@code join} 会把受检异常包进 {@link CompletionException}；这里解包成原始异常，
     * 否则全局异常处理器认不出 {@code AiException} / {@code BusinessException}，会统一落到 500。
     */
    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    private record Timed<T>(T value, long elapsedMs) {
    }
}
