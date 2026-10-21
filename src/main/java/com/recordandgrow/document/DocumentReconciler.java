package com.recordandgrow.document;

import com.recordandgrow.config.DocumentProperties;
import com.recordandgrow.document.queue.DocumentIngestQueue;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 对账任务：补发丢失的消息，不是标记失败。
 *
 * <p>只看 {@code last_progress_at}：{@code updated_at} 在消费过程中不会变，
 * 用它会把正在跑的大文档误判成卡住，重发 parse 会清空已回填的向量。
 * 每批 embedding 写完都会刷心跳，所以「10 分钟没动静」才是真的卡住。
 *
 * <p>{@code retry_count} 封顶后判 FAILED，避免「MQ 重投 + 对账补发」叠加成无限循环。
 */
@Component
@ConditionalOnProperty(name = "recordandgrow.document.reconcile.enabled",
        havingValue = "true", matchIfMissing = true)
public class DocumentReconciler {

    private static final Logger log = LoggerFactory.getLogger(DocumentReconciler.class);

    private final DocumentMapper documentMapper;
    private final DocumentIngestService service;
    private final DocumentIngestQueue queue;
    private final DocumentProperties properties;

    private ScheduledExecutorService scheduler;

    public DocumentReconciler(DocumentMapper documentMapper,
                              DocumentIngestService service,
                              DocumentIngestQueue queue,
                              DocumentProperties properties) {
        this.documentMapper = documentMapper;
        this.service = service;
        this.queue = queue;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        Duration interval = properties.getReconcile().getInterval();
        if (interval.isZero() || interval.isNegative()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "document-reconcile");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::safeReconcile,
                interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("文档对账任务已启动: interval={}, staleAfter={}",
                interval, properties.getReconcile().getStaleAfter());
    }

    @PreDestroy
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void safeReconcile() {
        try {
            reconcile();
        } catch (RuntimeException e) {
            log.warn("对账任务执行失败", e);
        }
    }

    /**
     * 扫一次卡住的文档并补发。
     *
     * @return 补发的条数（判失败的不计）
     */
    public int reconcile() {
        DocumentProperties.Reconcile config = properties.getReconcile();
        List<Document> stale = documentMapper.selectStale(config.getStaleAfter().toSeconds());
        int resent = 0;
        for (Document document : stale) {
            Long id = document.getId();
            int retries = document.getRetryCount() == null ? 0 : document.getRetryCount();
            if (retries >= config.getMaxRetries()) {
                service.markFailed(id, "多次重试仍失败，请手动重新索引");
                continue;
            }
            documentMapper.bumpRetryCount(id);
            try {
                if (DocumentStatus.EMBEDDING.name().equals(document.getStatus())) {
                    queue.sendEmbed(id);
                } else {
                    queue.sendParse(id);
                }
                resent++;
            } catch (RuntimeException e) {
                // MQ 没恢复：保持原状，下一轮再试；retry_count 已经 +1，封顶后会被判失败
                log.warn("对账补发失败: id={}, status={}, error={}", id, document.getStatus(), e.toString());
            }
        }
        if (resent > 0) {
            log.info("对账补发 {} 条文档", resent);
        }
        return resent;
    }
}
