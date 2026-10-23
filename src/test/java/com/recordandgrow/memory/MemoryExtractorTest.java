package com.recordandgrow.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryExtractorTest {

    @Test
    void parseShouldTolerateFenceAndNoise() {
        String content = """
                好的，结果如下：
                ```json
                [{"content":"用户每天早起跑步","type":"FACT","confidence":0.9},
                 {"content":"用户偏好远程办公","type":"INFERRED","confidence":0.7}]
                ```
                """;
        List<MemoryCandidate> candidates = MemoryExtractor.parse(content);
        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).proposedType()).isEqualTo(MemoryType.FACT);
        assertThat(candidates.get(1).proposedType()).isEqualTo(MemoryType.INFERRED);
    }

    @Test
    void parseShouldCapAtTwoAndSkipBlankContent() {
        String content = """
                [{"content":"","type":"FACT"},
                 {"content":"A","type":"UNKNOWN"},
                 {"content":"B","type":"PENDING"},
                 {"content":"C","type":"FACT"}]
                """;
        List<MemoryCandidate> candidates = MemoryExtractor.parse(content);
        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).content()).isEqualTo("A");
        assertThat(candidates.get(0).proposedType()).isEqualTo(MemoryType.INFERRED);
        assertThat(candidates.get(1).content()).isEqualTo("B");
        assertThat(candidates.get(1).proposedType()).isEqualTo(MemoryType.INFERRED);
    }

    @Test
    void parseShouldReturnEmptyOnBadJson() {
        assertThat(MemoryExtractor.parse("not json")).isEmpty();
        assertThat(MemoryExtractor.parse(null)).isEmpty();
        assertThat(MemoryExtractor.parse("[]")).isEmpty();
    }
}
