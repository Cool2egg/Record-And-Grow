package com.recordandgrow.document.queue;

import com.recordandgrow.config.DocumentProperties;
import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.infra.exception.ErrorCode;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * RocketMQ 生产者：两个 topic 各发一条「触发器」消息。
 *
 * <p>启动失败（broker 没起、namesrv 连不上）只记日志并置 {@code running=false}，
 * 不阻断应用启动；此时上传返回 50302，记录留在库里等对账补发。
 *
 * <p>消息体只放 documentId 与 stage：文件在磁盘、状态在 DB，消息没必要携带内容。
 * key 用 documentId，排查时可按 key 查消息轨迹。
 */
@Component
@ConditionalOnProperty(name = "recordandgrow.document.queue.mode", havingValue = "rocketmq")
public class RocketMqIngestQueue implements DocumentIngestQueue, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RocketMqIngestQueue.class);

    private final DocumentProperties properties;
    private volatile DefaultMQProducer producer;
    private volatile boolean running;

    public RocketMqIngestQueue(DocumentProperties properties) {
        this.properties = properties;
    }

    @Override
    public void sendParse(Long documentId) {
        send(properties.getQueue().getParseTopic(), documentId, "PARSE");
    }

    @Override
    public void sendEmbed(Long documentId) {
        send(properties.getQueue().getEmbedTopic(), documentId, "EMBED");
    }

    private void send(String topic, Long documentId, String stage) {
        DefaultMQProducer current = producer;
        if (!running || current == null) {
            throw new BusinessException(ErrorCode.QUEUE_UNAVAILABLE, "消息队列未就绪");
        }
        String body = "{\"documentId\":" + documentId + ",\"stage\":\"" + stage + "\"}";
        Message message = new Message(topic, body.getBytes(StandardCharsets.UTF_8));
        message.setKeys(String.valueOf(documentId));
        try {
            current.send(message, properties.getQueue().getSendTimeout().toMillis());
            log.debug("已发送文档消息: topic={}, id={}, stage={}", topic, documentId, stage);
        } catch (Exception e) {
            log.warn("发送文档消息失败: topic={}, id={}, error={}", topic, documentId, e.toString());
            throw new BusinessException(ErrorCode.QUEUE_UNAVAILABLE, "消息发送失败");
        }
    }

    @Override
    public synchronized void start() {
        DocumentProperties.Queue config = properties.getQueue();
        try {
            DefaultMQProducer created = new DefaultMQProducer(config.getProducerGroup());
            created.setNamesrvAddr(config.getNamesrv());
            created.setSendMsgTimeout((int) config.getSendTimeout().toMillis());
            created.start();
            this.producer = created;
            this.running = true;
            log.info("RocketMQ producer 已启动: namesrv={}, group={}",
                    config.getNamesrv(), config.getProducerGroup());
        } catch (Exception e) {
            this.running = false;
            log.error("RocketMQ producer 启动失败，上传将返回 50302，直到 MQ 恢复并重启应用: namesrv={}",
                    config.getNamesrv(), e);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        DefaultMQProducer current = producer;
        if (current != null) {
            current.shutdown();
            producer = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 比消费者先启动、后关闭。 */
    @Override
    public int getPhase() {
        return 0;
    }
}
