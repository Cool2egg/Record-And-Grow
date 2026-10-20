package com.recordandgrow.document.mq;

import com.recordandgrow.config.DocumentProperties;
import com.recordandgrow.document.DocumentIngestService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 阶段一消费者：解析 + 切块 + 发 embed 消息。
 */
@Component
@ConditionalOnProperty(name = "recordandgrow.document.queue.mode", havingValue = "rocketmq")
public class DocumentParseConsumer extends AbstractDocumentConsumer {

    public DocumentParseConsumer(DocumentProperties properties, DocumentIngestService service) {
        super(properties, service);
    }

    @Override
    protected String topic() {
        return properties.getQueue().getParseTopic();
    }

    @Override
    protected String consumerGroupSuffix() {
        return "parse-consumer";
    }

    @Override
    protected String stage() {
        return "PARSE";
    }

    @Override
    protected void handle(Long documentId) {
        service.ingestParse(documentId);
    }
}
