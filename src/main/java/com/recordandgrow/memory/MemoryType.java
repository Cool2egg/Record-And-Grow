package com.recordandgrow.memory;

/**
 * 长期记忆三态。
 *
 * <p>状态机只有一个方向：{@link #PENDING} 经用户确认后变成 {@link #FACT} 或 {@link #INFERRED}，
 * 确认前不参与回答；拒绝等价于软删。FACT / INFERRED 不再互相改写，需要改就删了重建。
 */
public enum MemoryType {

    /** AI 提炼的候选记忆，尚未确认，不参与回答。 */
    PENDING,

    /** 用户事实：用户主动录入或确认“确实如此”。 */
    FACT,

    /** 用户确认过的推断：AI 的归纳，用户认可其成立，但措辞上仍要保留推断语气。 */
    INFERRED;

    /** 宽松解析，供接口参数使用；不认识返回 {@code null}。 */
    public static MemoryType parse(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase();
        for (MemoryType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return null;
    }

    /** 是否是可用于回答的可信记忆。 */
    public boolean isTrusted() {
        return this == FACT || this == INFERRED;
    }
}
