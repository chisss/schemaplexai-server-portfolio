package com.schemaplexai.service.agent.tool.sandbox;

import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

/**
 * 代码沙箱执行请求
 */
@Data
@Builder
@Jacksonized
public class CodeExecRequest {

    /** 编程语言: javascript */
    private String language;

    /** 待执行代码 */
    private String code;

    /** 超时毫秒数 */
    private int timeoutMs;
}
