package com.recordandgrow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Skills 配置（模块十二）。技能是 Markdown 文件，命中触发词时注入回答 Prompt。
 */
@ConfigurationProperties(prefix = "recordandgrow.skills")
public class SkillProperties {

    private boolean enabled = true;

    /** 技能目录；不存在时静默跳过。 */
    private String dir = "./skills";

    /** 单轮最多注入几个技能，防止 Prompt 膨胀。 */
    private int maxSkills = 2;

    /** 单个技能正文注入上限。 */
    private int maxBodyChars = 3000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public int getMaxSkills() {
        return maxSkills;
    }

    public void setMaxSkills(int maxSkills) {
        this.maxSkills = maxSkills;
    }

    public int getMaxBodyChars() {
        return maxBodyChars;
    }

    public void setMaxBodyChars(int maxBodyChars) {
        this.maxBodyChars = maxBodyChars;
    }
}
