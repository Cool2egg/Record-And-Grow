package com.recordandgrow.graph;

import com.recordandgrow.config.GraphProperties;
import com.recordandgrow.retrieval.query.KeywordExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphRetrieverTest {

    @Test
    void termsShouldUseAsciiWordsAndCjkBigrams() {
        List<String> terms = GraphRetriever.terms("Kafka 和 Redis 有什么关系", new KeywordExtractor());
        assertThat(terms).contains("kafka", "redis");
        assertThat(terms).anyMatch(term -> term.length() == 2);
    }

    @Test
    void retrieveShouldReturnSearchedEmptyWhenDisabled() {
        GraphProperties properties = new GraphProperties();
        properties.setEnabled(false);
        GraphStore store = mock(GraphStore.class);
        GraphRetriever retriever = new GraphRetriever(store, properties, new KeywordExtractor());

        GraphRetrievalResult result = retriever.retrieve("Kafka");

        assertThat(result.searched()).isFalse();
        assertThat(result.degraded()).isFalse();
    }

    @Test
    void retrieveShouldMapHits() {
        GraphProperties properties = new GraphProperties();
        GraphStore store = mock(GraphStore.class);
        when(store.search(anyString(), anyList(), anyInt())).thenReturn(List.of(
                new GraphHit(1L, "Kafka", "属于", "消息队列", 9L, "笔记")));
        GraphRetriever retriever = new GraphRetriever(store, properties, new KeywordExtractor());

        GraphRetrievalResult result = retriever.retrieve("Kafka");

        assertThat(result.searched()).isTrue();
        assertThat(result.hits()).singleElement()
                .satisfies(hit -> assertThat(hit.target()).isEqualTo("消息队列"));
    }
}
