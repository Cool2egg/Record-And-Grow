package com.recordandgrow.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Skills 配置注册。技能目录不存在时注册表为空，不影响启动。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SkillProperties.class)
public class SkillConfig {
}
