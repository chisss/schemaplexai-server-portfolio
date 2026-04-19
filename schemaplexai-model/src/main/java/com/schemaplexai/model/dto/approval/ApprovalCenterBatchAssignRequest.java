package com.schemaplexai.model.dto.approval;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 统一审批中心批量指派请求
 */
@Data
public class ApprovalCenterBatchAssignRequest {

    /** quality_issue/security_incident */
    @NotBlank(message = "审批类型不能为空")
    private String approvalType;

    @NotEmpty(message = "审批项不能为空")
    private List<String> ids;

    @NotBlank(message = "处理人不能为空")
    private String assigneeId;

    /** 处理人名称，安全事件会写入快照 */
    private String assigneeName;

    /** 处置说明，安全事件必填 */
    private String comment;
}
