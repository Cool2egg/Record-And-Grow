package com.recordandgrow.document;

/**
 * 把 float[] 序列化成 pgvector 接受的文本字面量 {@code [0.1,0.2,...]}。
 *
 * <p>用 {@link Float#toString(float)} 而不是 {@code String.format}：
 * 它按 JDK 规范永远输出 '.'，与默认 Locale 无关，不会在德语等环境下变成逗号；
 * 同时保证 float 的精确往返。
 */
public final class VectorLiterals {

    private VectorLiterals() {
    }

    public static String toLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("向量不能为空");
        }
        StringBuilder builder = new StringBuilder(vector.length * 10);
        builder.append('[');
        for (int i = 0; i < vector.length; i++) {
            float value = vector[i];
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("向量包含非有限值: " + value);
            }
            if (i > 0) {
                builder.append(',');
            }
            builder.append(Float.toString(value));
        }
        return builder.append(']').toString();
    }
}
