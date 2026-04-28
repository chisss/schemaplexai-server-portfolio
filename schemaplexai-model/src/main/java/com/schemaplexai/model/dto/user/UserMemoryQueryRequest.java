package com.schemaplexai.model.dto.user;

import lombok.Data;

/**
 * 用户记忆查询请求
 */
@Data
public class UserMemoryQueryRequest {

    /** Agent ID */
    private String agentId;

    /** 作用域 */
    private String memoryScope;

    /** 记忆类型 */
    private String memoryKind;

    /** 状态 */
    private String status;

    /** 关键词 */
    private String keyword;
}
