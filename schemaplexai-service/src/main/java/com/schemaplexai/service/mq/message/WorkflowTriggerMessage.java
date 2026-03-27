package com.schemaplexai.service.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工作流触发消息体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowTriggerMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Spec ID */
    private String specId;

    /** 文档类型 */
    private String docType;

    /** 触发类型 */
    private String triggerType;

    /** 租户 ID */
    private String tenantId;

    /** 指定工作流模板 ID */
    private String workflowTemplateId;

    /** 触发人 */
    private String triggerBy;

    /** 触发时间 */
    private LocalDateTime triggeredAt;

    /** 幂等请求ID */
    private String requestId;
}
