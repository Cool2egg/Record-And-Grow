package com.recordandgrow.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphExtractorTest {

    @Test
    void parseShouldReadFencedArray() {
        String content = """
                抽取结果：
                ```json
                [{"source":"Kafka","relation":"属于","target":"消息队列"},
                 {"source":"Redis","relation":"用于","target":"缓存"}]
                ```
                """;
        List<GraphTriple> triples = GraphExtractor.parse(content, 10);
        assertThat(triples).hasSize(2);
        assertThat(triples.get(0).source()).isEqualTo("Kafka");
        assertThat(triples.get(0).relation()).isEqualTo("属于");
    }

    @Test
    void parseShouldSkipInvalidAndRespectLimit() {
        String content = """
                [{"source":"A","relation":"","target":"B"},
                 {"source":"A","relation":"等于","target":"A"},
                 {"source":"A","relation":"包含","target":"B"},
                 {"source":"C","relation":"包含","target":"D"}]
                """;
        List<GraphTriple> triples = GraphExtractor.parse(content, 1);
        assertThat(triples).hasSize(1);
        assertThat(triples.get(0).source()).isEqualTo("A");
        assertThat(triples.get(0).target()).isEqualTo("B");
    }

    @Test
    void parseShouldReturnEmptyOnBadJson() {
        assertThat(GraphExtractor.parse("nope", 10)).isEmpty();
        assertThat(GraphExtractor.parse("[]", 10)).isEmpty();
        assertThat(GraphExtractor.parse(null, 10)).isEmpty();
    }
}
