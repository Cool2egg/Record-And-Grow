package com.recordandgrow.memory;

import com.recordandgrow.document.Document;
import com.recordandgrow.document.DocumentMapper;
import com.recordandgrow.graph.GraphHit;
import com.recordandgrow.graph.GraphStore;
import com.recordandgrow.graph.GraphTriple;
import com.recordandgrow.memory.dto.ConversationResponse;
import com.recordandgrow.retrieval.query.TimeWindow;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块七/十的真库测试：Flyway V3/V4 建表、会话历史、三态记忆、图谱写入与 1 跳检索。
 */
@Testcontainers
@SpringBootTest
@Transactional
class MemoryPersistenceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("recordandgrow")
            .withUsername("recordandgrow")
            .withPassword("recordandgrow");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private GraphStore graphStore;

    @Autowired
    private DocumentMapper documentMapper;

    @Test
    void conversationHistoryShouldKeepRecentMessages() {
        ConversationResponse conversation = conversationService.create("测试会话");
        conversationService.append(conversation.id(), MessageRole.USER, "第一问", null);
        conversationService.append(conversation.id(), MessageRole.ASSISTANT, "第一答", "{\"items\":[]}");
        conversationService.append(conversation.id(), MessageRole.USER, "第二问", null);

        ConversationHistory history = conversationService.history(conversation.id());

        assertThat(history.messages()).hasSize(3);
        assertThat(history.messages().get(0).content()).isEqualTo("第一问");
        assertThat(history.messages().get(2).role()).isEqualTo(com.recordandgrow.ai.ChatMessage.Role.USER);
        assertThat(messageMapper.countByConversation(conversation.id())).isEqualTo(3);
    }

    @Test
    void pendingMemoryShouldBecomeInferredAfterConfirm() {
        Long id = memoryService.addCandidate(new MemoryCandidate("可能偏好远程办公",
                MemoryType.INFERRED, 0.7, "[]", MemorySource.AI_EXTRACTED, null));
        assertThat(id).isNotNull();

        var confirmed = memoryService.confirm(id);

        assertThat(confirmed.type()).isEqualTo("INFERRED");
        assertThat(confirmed.proposedType()).isNull();
        assertThat(confirmed.confirmedAt()).isNotNull();
        assertThat(memoryService.search("远程", 5)).singleElement()
                .satisfies(memory -> assertThat(memory.id()).isEqualTo(id));
        assertThat(memoryService.search("远程", 5).get(0).type()).isEqualTo("INFERRED");
    }

    @Test
    void graphShouldRoundTripOneHop() {
        Document document = new Document();
        document.setTitle("Kafka 笔记");
        document.setFileName("kafka.md");
        document.setMimeType("text/markdown");
        document.setFileSize(100L);
        document.setStoragePath("data/documents/kafka.md");
        document.setContentSha256("a".repeat(64));
        document.setStatus("READY");
        documentMapper.insert(document);

        graphStore.replaceDocument(document.getId(), List.of(
                new GraphTriple("Kafka", "属于", "消息队列"),
                new GraphTriple("Redis", "用于", "缓存")));

        List<GraphHit> hits = graphStore.search(List.of("kafka"), 5);

        assertThat(hits).singleElement()
                .satisfies(hit -> {
                    assertThat(hit.source()).isEqualTo("Kafka");
                    assertThat(hit.relation()).isEqualTo("属于");
                    assertThat(hit.target()).isEqualTo("消息队列");
                    assertThat(hit.documentId()).isEqualTo(document.getId());
                });
        assertThat(graphStore.countNodes()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void timeWindowConstructorIsUsableInTest() {
        TimeWindow window = new TimeWindow(OffsetDateTime.now().minusDays(1), OffsetDateTime.now(),
                com.recordandgrow.retrieval.query.TimeBasis.DEFAULT, false);
        assertThat(window.start()).isBefore(window.end());
    }
}
