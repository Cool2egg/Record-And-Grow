package com.recordandgrow.memory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户手工添加一条事实记忆。手工录入直接是 FACT，不需要确认。
 */
public record MemoryCreateRequest(
        @NotBlank @Size(max = 500) String content) {
}
