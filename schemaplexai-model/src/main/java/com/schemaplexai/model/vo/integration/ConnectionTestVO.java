package com.schemaplexai.model.vo.integration;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 连接测试结果视图对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionTestVO {

    /** 是否连接成功 */
    private Boolean connected;

    /** 延迟(毫秒) */
    private Long latencyMs;

    /** 权限范围列表 */
    private List<String> scopes;

    /** 认证用户名 */
    private String username;

    /** 失败原因 */
    private String errorMessage;
}
