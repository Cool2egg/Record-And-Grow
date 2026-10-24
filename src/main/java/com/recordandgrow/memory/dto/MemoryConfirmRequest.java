package com.recordandgrow.memory.dto;

import jakarta.validation.constraints.Size;

/**
 * 确认记忆的可选请求体（模块十三 §5.3）。
 *
 * <p>两个字段都可空：{@code content} 为空表示原样确认，非空表示「确认并改措辞」；
 * {@code type} 为空表示落到候选的 {@code proposedType}，非空表示显式覆盖
 * （前端「改成事实」用 {@code FACT}）。
 *
 * @param content 修改后的正文；最多 500 字
 * @param type    显式指定 FACT / INFERRED；PENDING 不允许
 */
public record MemoryConfirmRequest(
        @Size(max = 500, message = "content 长度不能超过 500") String content,
        String type) {
}
