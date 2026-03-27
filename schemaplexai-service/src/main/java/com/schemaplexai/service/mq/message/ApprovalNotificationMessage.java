package com.schemaplexai.service.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 审批通知消息体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalNotificationMessage implements Serializable {

    /** 工作流实例 ID */
    private String instanceId;

    /** 节点 ID */
    private String nodeId;

    /** 审核人角色列表 */
    private List<String> reviewerRoles;

    /** 租户 ID */
    private String tenantId;
}
