package com.recordandgrow.document.queue;

import com.recordandgrow.document.DocumentIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * local 模式：不经过 broker，直接调 service 的同步入口。
 *
 * <p>用可注入的 {@link Executor} 而不是同步执行：
 * <ul>
 *   <li>默认虚拟线程执行器 → 上传接口立即返回，大文件不会占住 HTTP 线程；</li>
 *   <li>测试注入 {@code Runnable::run} → 变成同步执行，断言确定。</li>
 * </ul>
 *
 * <p>没有 MQ 的重试：本地一次失败就标 FAILED（测试与演示都能立刻看到结果）。
 * 用 {@link ObjectProvider} 拿 service 是为了打破 Service → Queue → Service 的循环依赖。
 */
@Component
@ConditionalOnProperty(name = "recordandgrow.document.queue.mode",
        havingValue = "local", matchIfMissing = true)
public class LocalIngestQueue implements DocumentIngestQueue {

    private static final Logger log = LoggerFactory.getLogger(LocalIngestQueue.class);

    private final ObjectProvider<DocumentIngestService> serviceProvider;
    private final Executor executor;

    public LocalIngestQueue(ObjectProvider<DocumentIngestService> serviceProvider,
                            @Qualifier("documentIngestExecutor") Executor executor) {
        this.serviceProvider = serviceProvider;
        this.executor = executor;
    }

    @Override
    public void sendParse(Long documentId) {
        executor.execute(() -> run(documentId, true));
    }

    @Override
    public void sendEmbed(Long documentId) {
        executor.execute(() -> run(documentId, false));
    }

    private void run(Long documentId, boolean parse) {
        DocumentIngestService service = serviceProvider.getObject();
        try {
            if (parse) {
                service.ingestParse(documentId);
            } else {
                service.ingestEmbed(documentId);
            }
        } catch (RuntimeException e) {
            // local 模式没有 MQ 重试，失败即终态；异常不能再往上抛给 HTTP 线程。
            log.warn("local 模式处理失败: documentId={}, stage={}, error={}",
                    documentId, parse ? "PARSE" : "EMBED", e.toString());
            try {
                service.markFailed(documentId, e.getMessage());
            } catch (RuntimeException markError) {
                log.error("标记文档失败时再次出错: documentId={}", documentId, markError);
            }
        }
    }
}
