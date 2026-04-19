package com.schemaplexai.service.integration.platform;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 连通性测试结果
 */
@Data
@Builder
public class ConnectionTestResult {

    private boolean connected;
    private long latencyMs;
    /** 认证用户名 */
    private String username;
    /** 权限范围 */
    private List<String> scopes;
    /** 失败原因 */
    private String errorMessage;
}
