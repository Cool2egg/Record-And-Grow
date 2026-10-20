package com.recordandgrow.document.mq;

import com.recordandgrow.config.DocumentProperties;
import com.recordandgrow.document.DocumentIngestService;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 消费端的重试边界：首投失败重投、第 4 次失败标 FAILED 并确认、坏消息直接确认。
 *
 * <p>这里不启动 broker，只构造 MessageExt 直接调监听器。
 */
class AbstractDocumentConsumerTest {

    private DocumentIngestService service;
    private DocumentProperties properties;

    @BeforeEach
    void setUp() {
        service = mock(DocumentIngestService.class);
        properties = new DocumentProperties();
        properties.getQueue().setMaxReconsumeTimes(3);
    }

    @Test
    void successShouldAcknowledge() {
        RecordingConsumer consumer = new RecordingConsumer(properties, service);

        ConsumeConcurrentlyStatus status = consumer.consumeMessage(
                List.of(message(7L, 0)), null);

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        assertThat(consumer.handled).containsExactly(7L);
        verify(service, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void firstFailureShouldAskForRetry() {
        ThrowingConsumer consumer = new ThrowingConsumer(properties, service);

        ConsumeConcurrentlyStatus status = consumer.consumeMessage(
                List.of(message(7L, 0)), null);

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        verify(service, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void thirdDeliveryFailureShouldStillRetry() {
        ThrowingConsumer consumer = new ThrowingConsumer(properties, service);

        // getReconsumeTimes() == 2 表示第 3 次投递，还在重试次数内
        ConsumeConcurrentlyStatus status = consumer.consumeMessage(
                List.of(message(7L, 2)), null);

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        verify(service, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void fourthDeliveryFailureShouldMarkFailedAndAcknowledge() {
        ThrowingConsumer consumer = new ThrowingConsumer(properties, service);

        // 上限 3 次重试 = 最多 4 次投递；第 4 次（reconsumeTimes == 3）失败即终态
        ConsumeConcurrentlyStatus status = consumer.consumeMessage(
                List.of(message(8L, 3)), null);

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(service).markFailed(eq(8L), anyString());
    }

    @Test
    void failureBeyondLimitShouldMarkFailedAndAcknowledge() {
        ThrowingConsumer consumer = new ThrowingConsumer(properties, service);

        ConsumeConcurrentlyStatus status = consumer.consumeMessage(
                List.of(message(8L, 4)), null);

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(service).markFailed(eq(8L), anyString());
    }

    @Test
    void badBodyShouldBeAcknowledgedWithoutCallingService() {
        RecordingConsumer consumer = new RecordingConsumer(properties, service);
        MessageExt message = new MessageExt();
        message.setBody("not json".getBytes(StandardCharsets.UTF_8));

        ConsumeConcurrentlyStatus status = consumer.consumeMessage(List.of(message), null);

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        assertThat(consumer.handled).isEmpty();
    }

    @Test
    void markFailedErrorShouldNotBlockAcknowledging() {
        ThrowingConsumer consumer = new ThrowingConsumer(properties, service);
        doThrow(new IllegalStateException("db down")).when(service).markFailed(anyLong(), anyString());

        ConsumeConcurrentlyStatus status = consumer.consumeMessage(
                List.of(message(9L, 4)), null);

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
    }

    private static MessageExt message(Long documentId, int reconsumeTimes) {
        MessageExt message = new MessageExt();
        message.setReconsumeTimes(reconsumeTimes);
        message.setBody(("{\"documentId\":" + documentId + ",\"stage\":\"PARSE\"}")
                .getBytes(StandardCharsets.UTF_8));
        return message;
    }

    /** 记录收到的 id，不抛异常。 */
    private static final class RecordingConsumer extends AbstractDocumentConsumer {

        private final java.util.List<Long> handled = new java.util.ArrayList<>();

        private RecordingConsumer(DocumentProperties properties, DocumentIngestService service) {
            super(properties, service);
        }

        @Override
        protected String topic() {
            return "test-topic";
        }

        @Override
        protected String consumerGroupSuffix() {
            return "test-consumer";
        }

        @Override
        protected String stage() {
            return "PARSE";
        }

        @Override
        protected void handle(Long documentId) {
            handled.add(documentId);
        }
    }

    /** 永远失败，用来验证重试边界。 */
    private static final class ThrowingConsumer extends AbstractDocumentConsumer {

        private ThrowingConsumer(DocumentProperties properties, DocumentIngestService service) {
            super(properties, service);
        }

        @Override
        protected String topic() {
            return "test-topic";
        }

        @Override
        protected String consumerGroupSuffix() {
            return "test-consumer";
        }

        @Override
        protected String stage() {
            return "PARSE";
        }

        @Override
        protected void handle(Long documentId) {
            throw new IllegalStateException("boom");
        }
    }
}
