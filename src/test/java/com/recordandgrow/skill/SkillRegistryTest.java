package com.recordandgrow.skill;

import com.recordandgrow.config.SkillProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SkillRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void parseShouldReadFrontMatter() {
        Skill skill = SkillRegistry.parse(Path.of("weekly.md"), """
                ---
                name: 周报生成
                description: 整理周报
                triggers: 周报, 总结
                ---
                正文一
                正文二
                """, 3000);

        assertThat(skill).isNotNull();
        assertThat(skill.name()).isEqualTo("周报生成");
        assertThat(skill.triggers()).containsExactly("周报", "总结");
        assertThat(skill.body()).contains("正文一");
    }

    @Test
    void parseShouldFallbackToHeading() {
        Skill skill = SkillRegistry.parse(Path.of("plan.md"), """
                # 学习计划
                > 触发词：学习计划, 怎么学
                先看时间线，再看文档。
                """, 3000);

        assertThat(skill.name()).isEqualTo("学习计划");
        assertThat(skill.triggers()).contains("学习计划", "怎么学");
        assertThat(skill.description()).contains("先看时间线");
    }

    @Test
    void reloadAndMatchShouldHitTriggers() throws IOException {
        Files.writeString(tempDir.resolve("weekly.md"), """
                ---
                name: 周报生成
                triggers: 周报, 总结
                ---
                输出三段式周报。
                """);
        Files.writeString(tempDir.resolve("other.md"), """
                ---
                name: 其他
                triggers: 别的
                ---
                无关内容。
                """);

        SkillProperties properties = new SkillProperties();
        properties.setDir(tempDir.toString());
        SkillRegistry registry = new SkillRegistry(properties);
        assertThat(registry.reload()).isEqualTo(2);

        assertThat(registry.match("帮我写一份这周的周报")).singleElement()
                .satisfies(skill -> assertThat(skill.name()).isEqualTo("周报生成"));
        assertThat(registry.match("今天天气怎么样")).isEmpty();
    }
}
