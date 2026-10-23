package com.recordandgrow.graph;

/**
 * 一条实体关系三元组，抽取器的输出、存储层的输入。
 *
 * @param source   头实体
 * @param relation 关系
 * @param target   尾实体
 */
public record GraphTriple(String source, String relation, String target) {

    public GraphTriple {
        source = normalize(source, 100);
        relation = normalize(relation, 60);
        target = normalize(target, 100);
    }

    public boolean isValid() {
        return !source.isEmpty() && !relation.isEmpty() && !target.isEmpty()
                && !source.equalsIgnoreCase(target);
    }

    private static String normalize(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
