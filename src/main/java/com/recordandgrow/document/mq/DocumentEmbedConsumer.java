package com.recordandgrow.document.mq;

import com.recordandgrow.config.DocumentProperties;
import com.recordandgrow.document.DocumentIngestService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 阶段二消费者：按批回填向量。
 */
@Component
@ConditionalOnProperty(name = "recordandgrow.document.queue.mode", havingValue = "rocketmq")
public class DocumentEmbedConsumer extends AbstractDocumentConsumer {

    public DocumentEmbedConsumer(DocumentProperties properties, DocumentIngestService service) {
        super(properties, service);
    }

    @Override
    protected String topic() {
        return properties.getQueue().getEmbedTopic();
    }

    @Override
    protected String consumerGroupSuffix() {
        return "embed-consumer";
    }

    @Override
    protected String stage() {
        return "EMBED";
    }

    @Override
    protected void handle(Long documentId) {
        service.ingestEmbed(documentId);
    }
}
