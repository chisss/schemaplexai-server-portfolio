package com.schemaplexai.model.dto.approval;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 统一审批中心批量审批请求
 */
@Data
public class ApprovalCenterBatchReviewRequest {

    @NotEmpty(message = "审批项不能为空")
    private List<String> ids;

    /** approve/reject/request_modify */
    @NotBlank(message = "审批动作不能为空")
    private String decision;

    /** 审批意见 */
    private String comment;

    /** 退回重跑时的修改说明 */
    private String modifyInstruction;
}
