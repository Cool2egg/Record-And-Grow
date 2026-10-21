package com.recordandgrow.document.mq;

import com.recordandgrow.config.DocumentProperties;
import com.recordandgrow.document.DocumentIngestService;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 文档消费者基类：只做「解析消息 → 调同步入口 → 异常转重试」，不写业务。
 *
 * <p>重试边界的判定在 catch 里、不在入口：第 {@code maxReconsumeTimes + 1} 次投递
 * 仍要给最后一次机会，失败后才标 FAILED 并返回成功。
 * {@code MessageExt.getReconsumeTimes()} 首投为 0，第 4 次为 3（以 RocketMQ 实测日志为准，
 * 见验证文档）。
 *
 * <p>不返回 RECONSUME_LATER 的最后一次是刻意的：MQ 的 DLQ 只留作人工排查入口，
 * 自动重投会无限烧 embedding 预算。
 */
public abstract class AbstractDocumentConsumer implements MessageListenerConcurrently, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AbstractDocumentConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    protected final DocumentProperties properties;
    protected final DocumentIngestService service;

    private volatile DefaultMQPushConsumer consumer;
    private volatile boolean running;

    protected AbstractDocumentConsumer(DocumentProperties properties, DocumentIngestService service) {
        this.properties = properties;
        this.service = service;
    }

    protected abstract String topic();

    protected abstract String consumerGroupSuffix();

    /** 日志用的阶段名，如 PARSE / EMBED。 */
    protected abstract String stage();

    protected abstract void handle(Long documentId);

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages,
                                                    ConsumeConcurrentlyContext context) {
        for (MessageExt message : messages) {
            Long documentId = parseDocumentId(message);
            if (documentId == null) {
                // 坏消息重试也不会变好，直接确认掉
                log.warn("无法解析消息体，跳过: topic={}, msgId={}", topic(), message.getMsgId());
                continue;
            }
            try {
                handle(documentId);
            } catch (RuntimeException e) {
                int reconsumeTimes = message.getReconsumeTimes();
                log.warn("文档处理失败: stage={}, id={}, reconsumeTimes={}, error={}",
                        stage(), documentId, reconsumeTimes, e.toString());
                if (reconsumeTimes >= properties.getQueue().getMaxReconsumeTimes()) {
                    try {
                        service.markFailed(documentId, e.getMessage());
                    } catch (RuntimeException markError) {
                        log.error("标记文档失败时再次出错: id={}", documentId, markError);
                    }
                    log.warn("重试耗尽，已标记 FAILED 并确认消息: stage={}, id={}", stage(), documentId);
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                }
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    private Long parseDocumentId(MessageExt message) {
        byte[] body = message.getBody();
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            long id = node.path("documentId").asLong(0);
            return id > 0 ? id : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public synchronized void start() {
        DocumentProperties.Queue config = properties.getQueue();
        String group = config.getConsumerGroupPrefix() + "-" + consumerGroupSuffix();
        try {
            DefaultMQPushConsumer created = new DefaultMQPushConsumer(group);
            created.setNamesrvAddr(config.getNamesrv());
            // 单用户场景，4 个线程足够；默认 20 个只会白占线程
            created.setConsumeThreadMin(2);
            created.setConsumeThreadMax(4);
            created.setMaxReconsumeTimes(config.getMaxReconsumeTimes());
            created.subscribe(topic(), "*");
            created.registerMessageListener(this);
            created.start();
            this.consumer = created;
            this.running = true;
            log.info("RocketMQ 消费者已启动: group={}, topic={}", group, topic());
        } catch (Exception e) {
            this.running = false;
            log.error("RocketMQ 消费者启动失败: group={}, topic={}", group, topic(), e);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        DefaultMQPushConsumer current = consumer;
        if (current != null) {
            current.shutdown();
            consumer = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 晚于 producer 启动、早于它关闭。 */
    @Override
    public int getPhase() {
        return 100;
    }
}
