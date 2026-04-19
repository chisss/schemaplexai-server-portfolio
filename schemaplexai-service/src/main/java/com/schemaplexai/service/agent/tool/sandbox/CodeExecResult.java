package com.schemaplexai.service.agent.tool.sandbox;

import lombok.Builder;
import lombok.Data;

/**
 * 代码沙箱执行结果
 * <p>exitCode 语义:
 * <ul>
 *   <li>0 = 执行成功</li>
 *   <li>1 = 运行时错误</li>
 *   <li>2 = 执行超时</li>
 *   <li>3 = 语法/编译错误</li>
 *   <li>4 = 系统错误（并发满/服务禁用等）</li>
 * </ul>
 */
@Data
@Builder
public class CodeExecResult {

    private String stdout;
    private String stderr;
    private int exitCode;
    private long executionMs;
    private String language;
}
