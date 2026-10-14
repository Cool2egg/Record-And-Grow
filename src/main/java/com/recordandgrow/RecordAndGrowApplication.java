package com.recordandgrow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用入口。
 *
 * <p>RecordAndGrow：一个基于个人时间线、文档和对话记忆的个人上下文 Agent。
 */
@SpringBootApplication
public class RecordAndGrowApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecordAndGrowApplication.class, args);
    }
}
