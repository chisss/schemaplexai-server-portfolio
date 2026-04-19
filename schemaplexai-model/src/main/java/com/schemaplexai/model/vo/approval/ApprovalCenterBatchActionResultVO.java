package com.schemaplexai.model.vo.approval;

import lombok.Data;

import java.util.List;

/**
 * 批量操作结果
 */
@Data
public class ApprovalCenterBatchActionResultVO {

    private int totalCount;

    private int successCount;

    private int failedCount;

    private List<String> successIds;

    private List<ApprovalCenterBatchActionFailureVO> failures;
}
