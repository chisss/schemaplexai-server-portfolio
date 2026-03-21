package com.schemaplexai.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SchemaPlexAI 后端启动类
 */
@SpringBootApplication(scanBasePackages = "com.schemaplexai")
@MapperScan("com.schemaplexai.dao.mapper")
@EnableAsync
@EnableScheduling
public class SchemaPlexAIApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchemaPlexAIApplication.class, args);
    }
}
