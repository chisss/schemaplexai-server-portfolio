package com.schemaplexai.model.dto.approval;

import lombok.Data;

/**
 * 统一审批中心查询请求
 */
@Data
public class ApprovalCenterQueryRequest {

    /** 视图: pending/all/processed */
    private String view = "pending";

    /** 类型: workflow_review/quality_issue/security_incident */
    private String type;

    /** 关键字 */
    private String keyword;

    /** 页码 */
    private Integer page = 1;

    /** 页大小 */
    private Integer size = 20;
}
