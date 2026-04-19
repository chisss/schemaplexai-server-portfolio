package com.schemaplexai.service.approval;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.approval.ApprovalCenterBatchAssignRequest;
import com.schemaplexai.model.dto.approval.ApprovalCenterBatchReviewRequest;
import com.schemaplexai.model.dto.approval.ApprovalCenterQueryRequest;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.vo.approval.ApprovalCenterBatchActionResultVO;
import com.schemaplexai.model.vo.approval.ApprovalCenterItemVO;

/**
 * 统一审批中心服务
 */
public interface ApprovalCenterService {

    PageResult<ApprovalCenterItemVO> page(ApprovalCenterQueryRequest request);

    ApprovalCenterBatchActionResultVO batchReview(ApprovalCenterBatchReviewRequest request);

    ApprovalCenterBatchActionResultVO batchAssign(ApprovalCenterBatchAssignRequest request, SecurityAuditContext auditContext);

    byte[] exportAudit(ApprovalCenterQueryRequest request);

    default String resolveExportFileName() {
        return "approval-audit.csv";
    }
}
