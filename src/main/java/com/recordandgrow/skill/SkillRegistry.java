package com.recordandgrow.skill;

import com.recordandgrow.config.SkillProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 技能注册表：扫描目录下的 Markdown 文件，按触发词匹配。
 *
 * <p>选择文件而不是数据库：技能是项目资产，跟着仓库走、可 review、可热加载；
 * 单用户场景不需要在线编辑。
 */
@Service
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final SkillProperties properties;
    private volatile List<Skill> skills = List.of();

    public SkillRegistry(SkillProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    /** 重新扫描目录，返回加载的技能数。 */
    public synchronized int reload() {
        if (!properties.isEnabled()) {
            skills = List.of();
            return 0;
        }
        Path dir = Paths.get(properties.getDir());
        if (!Files.isDirectory(dir)) {
            log.info("skills directory not found, skip: {}", dir.toAbsolutePath());
            skills = List.of();
            return 0;
        }
        List<Skill> loaded = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> {
                        try {
                            Skill skill = parse(path, Files.readString(path, StandardCharsets.UTF_8),
                                    properties.getMaxBodyChars());
                            if (skill != null) {
                                loaded.add(skill);
                            }
                        } catch (IOException e) {
                            log.warn("skill read failed: {}", path.getFileName());
                        }
                    });
        } catch (IOException e) {
            log.warn("skills directory scan failed: {}", e.getMessage());
            return 0;
        }
        skills = List.copyOf(loaded);
        log.info("skills loaded: count={}, dir={}", skills.size(), dir.toAbsolutePath());
        return skills.size();
    }

    public List<Skill> all() {
        return skills;
    }

    /**
     * 按触发词命中数排序，最多返回 {@code max-skills} 个。
     * 没有命中就返回空，不做模糊匹配——技能注入宁缺毋滥。
     */
    public List<Skill> match(String question) {
        if (!properties.isEnabled() || question == null || question.isBlank() || skills.isEmpty()) {
            return List.of();
        }
        String normalized = question.toLowerCase(Locale.ROOT);
        List<Skill> matched = new ArrayList<>();
        for (Skill skill : skills) {
            long hits = skill.triggers().stream()
                    .filter(trigger -> !trigger.isBlank())
                    .filter(trigger -> normalized.contains(trigger.toLowerCase(Locale.ROOT)))
                    .count();
            if (hits > 0) {
                matched.add(skill);
            }
        }
        matched.sort(Comparator.comparingLong((Skill skill) -> skill.triggers().stream()
                        .filter(trigger -> normalized.contains(trigger.toLowerCase(Locale.ROOT)))
                        .count())
                .reversed());
        int max = Math.max(1, properties.getMaxSkills());
        return matched.size() <= max ? List.copyOf(matched) : List.copyOf(matched.subList(0, max));
    }

    static Skill parse(Path path, String content, int maxBodyChars) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String text = content.replace("\r\n", "\n").replace('\r', '\n');
        String name = null;
        String description = "";
        List<String> triggers = new ArrayList<>();
        String body = text;

        if (text.startsWith("---")) {
            int end = text.indexOf("\n---", 3);
            if (end > 0) {
                String header = text.substring(3, end);
                body = text.substring(end + 4);
                for (String line : header.split("\n")) {
                    String trimmed = line.trim();
                    int colon = trimmed.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    String key = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String value = trimmed.substring(colon + 1).trim();
                    switch (key) {
                        case "name" -> name = value;
                        case "description" -> description = value;
                        case "triggers", "trigger", "keywords" -> triggers.addAll(splitTriggers(value));
                        default -> {
                        }
                    }
                }
            }
        }

        if (name == null) {
            for (String line : body.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("# ") && name == null) {
                    name = trimmed.substring(2).trim();
                }
                if (trimmed.startsWith("> 触发词") || trimmed.startsWith("> triggers")) {
                    int colon = Math.max(trimmed.indexOf(':'), trimmed.indexOf('：'));
                    if (colon > 0) {
                        triggers.addAll(splitTriggers(trimmed.substring(colon + 1)));
                    }
                }
            }
        }
        if (name == null || name.isBlank()) {
            name = path.getFileName().toString().replaceFirst("\\.md$", "");
        }
        body = body.trim();
        if (body.length() > maxBodyChars) {
            body = body.substring(0, Math.max(1, maxBodyChars));
        }
        if (description.isBlank()) {
            description = body.lines()
                    .filter(line -> !line.isBlank())
                    .filter(line -> !line.startsWith("#"))
                    .filter(line -> !line.startsWith(">"))
                    .findFirst().orElse("").trim();
        }
        return new Skill(name, description, triggers, body, path.toString());
    }

    private static List<String> splitTriggers(String value) {
        List<String> triggers = new ArrayList<>();
        for (String part : value.split("[,，、;；\\s]+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                triggers.add(trimmed);
            }
        }
        return triggers;
    }
}
