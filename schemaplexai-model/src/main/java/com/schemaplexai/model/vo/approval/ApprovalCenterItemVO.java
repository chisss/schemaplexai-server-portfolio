package com.schemaplexai.model.vo.approval;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统一审批中心列表项
 */
@Data
public class ApprovalCenterItemVO {

    private String id;

    /** workflow_review/quality_issue/security_incident */
    private String approvalType;

    private String title;

    private String summary;

    private String status;

    private String statusLabel;

    private Boolean pending;

    private String specId;

    private String specName;

    private String workflowInstanceId;

    private String workflowNodeId;

    private String workflowNodeLabel;

    private String sourceId;

    private String sourceName;

    private String documentType;

    private String applicantId;

    private String applicantName;

    private String assigneeId;

    private String assigneeName;

    private String approverId;

    private String approverName;

    private LocalDateTime approverAt;

    private LocalDateTime deadline;

    private String actionUrl;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
