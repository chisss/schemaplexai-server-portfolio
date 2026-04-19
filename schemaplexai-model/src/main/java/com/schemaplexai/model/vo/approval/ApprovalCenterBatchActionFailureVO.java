package com.schemaplexai.model.vo.approval;

import lombok.Data;

/**
 * 批量操作失败项
 */
@Data
public class ApprovalCenterBatchActionFailureVO {

    private String id;

    private String reason;
}
