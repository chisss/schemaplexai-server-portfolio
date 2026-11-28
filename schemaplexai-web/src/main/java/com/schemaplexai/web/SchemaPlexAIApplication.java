package com.schemaplexai.web;

import com.schemaplexai.service.agent.tool.sandbox.WasmSandboxWorker;
import org.mybatis.spring.annotation.MapperScan;
import java.io.IOException;
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

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && "--wasm-sandbox-worker".equals(args[0])) {
            WasmSandboxWorker.start();
            return;
        }
        SpringApplication.run(SchemaPlexAIApplication.class, args);
    }
}
