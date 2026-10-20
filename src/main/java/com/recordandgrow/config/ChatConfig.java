package com.recordandgrow.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 对话模块的 Bean 装配。
 *
 * <p>{@link Clock} 和 {@link ZoneId} 单独暴露，时间窗解析与测试都从这里取，
 * 避免在业务代码里读 {@code ZoneId.systemDefault()}。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatProperties.class)
public class ChatConfig {

    @Bean
    public ZoneId chatZoneId(ChatProperties properties) {
        return ZoneId.of(properties.getTimezone());
    }

    @Bean
    public Clock chatClock(ZoneId chatZoneId) {
        return Clock.system(chatZoneId);
    }
}
