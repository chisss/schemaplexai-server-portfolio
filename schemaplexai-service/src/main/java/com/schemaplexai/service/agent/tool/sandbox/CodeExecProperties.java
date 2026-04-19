package com.schemaplexai.service.agent.tool.sandbox;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 代码沙箱配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "schemaplexai.sandbox.code-exec")
public class CodeExecProperties {

    /** 是否启用代码沙箱 */
    private boolean enabled = true;

    /** 默认超时毫秒 */
    private int defaultTimeoutMs = 5000;

    /** 最大允许超时毫秒 */
    private int maxTimeoutMs = 30000;

    /** 代码最大字符长度 */
    private int maxCodeLength = 50000;

    /** 输出最大字符长度 */
    private int maxOutputLength = 24000;

    /** 最大并发执行数 */
    private int maxConcurrentExecutions = 4;
}
