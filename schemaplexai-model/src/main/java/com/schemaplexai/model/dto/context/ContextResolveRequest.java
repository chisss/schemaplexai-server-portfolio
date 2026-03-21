package com.schemaplexai.model.dto.context;

import lombok.Data;

/**
 * 上下文解析请求 - 为Agent执行任务组装完整上下文
 */
@Data
public class ContextResolveRequest {

    /** Agent ID */
    private String agentId;

    /** 任务ID（关联的Spec或任务） */
    private String taskId;

    /** Spec ID（任务关联的Spec） */
    private String specId;

    /** Token预算上限（默认8000） */
    private Integer tokenBudget = 8000;

    /** 项目ID（用于加载项目级上下文） */
    private String projectId;
}
