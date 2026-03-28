package com.schemaplexai.service.tool.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.script.ScriptEngineManager;

/**
 * 脚本引擎配置
 */
@Configuration
public class ScriptEngineConfig {

    @Bean
    public ScriptEngineManager scriptEngineManager() {
        return new ScriptEngineManager();
    }
}
