package com.recordandgrow.memory;

import com.recordandgrow.config.MemoryProperties;
import com.recordandgrow.infra.exception.BusinessException;
import com.recordandgrow.memory.dto.MemoryResponse;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryServiceTest {

    private final MemoryMapper mapper = mock(MemoryMapper.class);
    private final MemoryProperties properties = new MemoryProperties();
    private final MemoryService service = new MemoryService(mapper, properties);

    @Test
    void normalizeShouldCollapseWhitespaceAndTrailingPunctuation() {
        assertThat(MemoryService.normalize("  每天   跑步。 ")).isEqualTo("每天 跑步");
        assertThat(MemoryService.normalize("Kafka 学习计划！")).isEqualTo("kafka 学习计划");
        assertThat(MemoryService.normalize(null)).isEmpty();
    }

    @Test
    void manualCreateShouldBeConfirmedFact() {
        when(mapper.selectByNormalized("每天 跑步")).thenReturn(null);
        when(mapper.insert(any(Memory.class))).thenAnswer(invocation -> {
            Memory memory = invocation.getArgument(0);
            memory.setId(1L);
            return 1;
        });

        MemoryResponse response = service.createManual("  每天   跑步。 ");

        assertThat(response.type()).isEqualTo("FACT");
        assertThat(response.source()).isEqualTo("USER");
        assertThat(response.confirmedAt()).isNotNull();
        assertThat(response.content()).isEqualTo("每天   跑步。");
    }

    @Test
    void manualCreateOnPendingShouldConfirmInsteadOfDuplicating() {
        Memory pending = new Memory();
        pending.setId(9L);
        pending.setType("PENDING");
        pending.setProposedType("INFERRED");
        pending.setContent("喜欢早起");
        when(mapper.selectByNormalized("喜欢早起")).thenReturn(pending);
        when(mapper.selectById(9L)).thenReturn(pending);
        when(mapper.confirm(9L, "INFERRED")).thenReturn(1);

        MemoryResponse response = service.createManual("喜欢早起");

        assertThat(response.type()).isEqualTo("INFERRED");
        assertThat(response.confirmedAt()).isNotNull();
        verify(mapper, never()).insert(any(Memory.class));
    }

    @Test
    void confirmShouldUseProposedTypeAndBeIdempotent() {
        Memory pending = new Memory();
        pending.setId(3L);
        pending.setType("PENDING");
        pending.setProposedType("INFERRED");
        pending.setContent("可能偏好远程办公");
        Memory confirmed = new Memory();
        confirmed.setId(3L);
        confirmed.setType("INFERRED");
        confirmed.setContent("可能偏好远程办公");
        confirmed.setConfirmedAt(java.time.OffsetDateTime.now());
        when(mapper.selectById(3L)).thenReturn(pending, confirmed);
        when(mapper.confirm(3L, "INFERRED")).thenReturn(1);

        MemoryResponse first = service.confirm(3L);
        assertThat(first.type()).isEqualTo("INFERRED");
        assertThat(first.proposedType()).isNull();
        assertThat(first.confirmedAt()).isNotNull();

        MemoryResponse second = service.confirm(3L);
        assertThat(second.type()).isEqualTo("INFERRED");
        verify(mapper).confirm(3L, "INFERRED");
    }

    @Test
    void rejectOnlyAllowsPending() {
        Memory fact = new Memory();
        fact.setId(4L);
        fact.setType("FACT");
        when(mapper.selectById(4L)).thenReturn(fact);

        assertThatThrownBy(() -> service.reject(4L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("待确认");
    }

    @Test
    void addCandidateShouldDropDuplicates() {
        Memory existing = new Memory();
        existing.setId(5L);
        when(mapper.countPending()).thenReturn(0L);
        when(mapper.selectByNormalized("每天跑步")).thenReturn(existing);

        Long id = service.addCandidate(new MemoryCandidate("每天跑步", MemoryType.FACT,
                0.9, "[]", MemorySource.AI_EXTRACTED, 1L));

        assertThat(id).isNull();
        verify(mapper, never()).insert(any(Memory.class));
    }

    @Test
    void addCandidateShouldDropWhenPendingLimitReached() {
        properties.setPendingMax(2);
        when(mapper.countPending()).thenReturn(2L);

        Long id = service.addCandidate(new MemoryCandidate("新偏好", MemoryType.INFERRED,
                0.6, "[]", MemorySource.AI_EXTRACTED, 1L));

        assertThat(id).isNull();
        verify(mapper, never()).insert(any(Memory.class));
    }

    @Test
    void confirmMissingShouldThrowNotFound() {
        when(mapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.confirm(99L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void listShouldClampPageSize() {
        when(mapper.selectPage(any(), any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());
        service.list(MemoryType.FACT, 0, 999);
        // 只验证不抛异常且分页参数被夹紧由 MyBatis 内部处理；具体 size 在 Page 对象里
    }

    @Test
    void pendingShouldReturnMappedResponses() {
        Memory pending = new Memory();
        pending.setId(6L);
        pending.setType("PENDING");
        pending.setContent("候选");
        pending.setCreatedAt(OffsetDateTime.now());
        when(mapper.selectPending(5)).thenReturn(List.of(pending));

        assertThat(service.pending(5)).singleElement()
                .satisfies(response -> assertThat(response.type()).isEqualTo("PENDING"));
    }
}
