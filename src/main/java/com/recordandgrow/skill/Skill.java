package com.recordandgrow.skill;

import java.util.List;

/**
 * 一个技能：一段可复用的流程/口吻指引。
 *
 * @param triggers 命中词；问题里出现任意一个即视为命中
 * @param source   文件路径，便于排查
 */
public record Skill(String name, String description, List<String> triggers,
                    String body, String source) {

    public Skill {
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
    }
}
