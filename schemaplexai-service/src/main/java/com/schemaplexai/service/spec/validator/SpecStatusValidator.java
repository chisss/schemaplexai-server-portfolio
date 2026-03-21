package com.schemaplexai.service.spec.validator;

import com.schemaplexai.common.enums.SpecDocTypeEnum;
import com.schemaplexai.common.enums.SpecStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Spec状态机校验器 — 封装生命周期状态转换规则
 *
 * 状态流转:
 * draft → requirements_review
 * requirements_review → requirements_approved | draft (驳回)
 * requirements_approved → design_review
 * design_review → design_approved | requirements_approved (驳回)
 * design_approved → tasks_review
 * tasks_review → ready | design_approved (驳回)
 * ready → in_progress
 * in_progress → completed
 * completed → acceptance
 * acceptance → archived | in_progress (驳回)
 */
@Component
public class SpecStatusValidator {

    /** 合法的状态转换关系 */
    private static final Map<String, Set<String>> TRANSITIONS = Map.ofEntries(
            Map.entry(SpecStatusEnum.DRAFT.getCode(),
                    Set.of(SpecStatusEnum.REQUIREMENTS_REVIEW.getCode())),
            Map.entry(SpecStatusEnum.REQUIREMENTS_REVIEW.getCode(),
                    Set.of(SpecStatusEnum.REQUIREMENTS_APPROVED.getCode(), SpecStatusEnum.DRAFT.getCode())),
            Map.entry(SpecStatusEnum.REQUIREMENTS_APPROVED.getCode(),
                    Set.of(SpecStatusEnum.DESIGN_REVIEW.getCode())),
            Map.entry(SpecStatusEnum.DESIGN_REVIEW.getCode(),
                    Set.of(SpecStatusEnum.DESIGN_APPROVED.getCode(), SpecStatusEnum.REQUIREMENTS_APPROVED.getCode())),
            Map.entry(SpecStatusEnum.DESIGN_APPROVED.getCode(),
                    Set.of(SpecStatusEnum.TASKS_REVIEW.getCode())),
            Map.entry(SpecStatusEnum.TASKS_REVIEW.getCode(),
                    Set.of(SpecStatusEnum.READY.getCode(), SpecStatusEnum.DESIGN_APPROVED.getCode())),
            Map.entry(SpecStatusEnum.READY.getCode(),
                    Set.of(SpecStatusEnum.IN_PROGRESS.getCode())),
            Map.entry(SpecStatusEnum.IN_PROGRESS.getCode(),
                    Set.of(SpecStatusEnum.COMPLETED.getCode())),
            Map.entry(SpecStatusEnum.COMPLETED.getCode(),
                    Set.of(SpecStatusEnum.ACCEPTANCE.getCode())),
            Map.entry(SpecStatusEnum.ACCEPTANCE.getCode(),
                    Set.of(SpecStatusEnum.ARCHIVED.getCode(), SpecStatusEnum.IN_PROGRESS.getCode()))
    );

    /** 文档类型 → 对应的review状态 */
    private static final Map<String, String> DOC_TYPE_TO_REVIEW = Map.of(
            SpecDocTypeEnum.REQUIREMENTS.getCode(), SpecStatusEnum.REQUIREMENTS_REVIEW.getCode(),
            SpecDocTypeEnum.DESIGN.getCode(), SpecStatusEnum.DESIGN_REVIEW.getCode(),
            SpecDocTypeEnum.TASKS.getCode(), SpecStatusEnum.TASKS_REVIEW.getCode()
    );

    /** 审批通过时，review/acceptance状态 → approved/archived状态 */
    private static final Map<String, String> REVIEW_TO_APPROVED = Map.of(
            SpecStatusEnum.REQUIREMENTS_REVIEW.getCode(), SpecStatusEnum.REQUIREMENTS_APPROVED.getCode(),
            SpecStatusEnum.DESIGN_REVIEW.getCode(), SpecStatusEnum.DESIGN_APPROVED.getCode(),
            SpecStatusEnum.TASKS_REVIEW.getCode(), SpecStatusEnum.READY.getCode(),
            SpecStatusEnum.ACCEPTANCE.getCode(), SpecStatusEnum.ARCHIVED.getCode()
    );

    /** 审批驳回时，review状态 → 回退状态 */
    private static final Map<String, String> REVIEW_TO_REJECTED = Map.of(
            SpecStatusEnum.REQUIREMENTS_REVIEW.getCode(), SpecStatusEnum.DRAFT.getCode(),
            SpecStatusEnum.DESIGN_REVIEW.getCode(), SpecStatusEnum.REQUIREMENTS_APPROVED.getCode(),
            SpecStatusEnum.TASKS_REVIEW.getCode(), SpecStatusEnum.DESIGN_APPROVED.getCode(),
            SpecStatusEnum.ACCEPTANCE.getCode(), SpecStatusEnum.IN_PROGRESS.getCode()
    );

    /**
     * 校验状态转换是否合法
     */
    public void validateTransition(String currentStatus, String targetStatus) {
        var allowedTargets = TRANSITIONS.get(currentStatus);
        if (allowedTargets == null || !allowedTargets.contains(targetStatus)) {
            throw new BusinessException(ResultCode.SPEC_STATUS_NOT_ALLOWED);
        }
    }

    /**
     * 根据文档类型获取对应的review状态
     */
    public String resolveReviewStatus(String docType) {
        var reviewStatus = DOC_TYPE_TO_REVIEW.get(docType);
        if (reviewStatus == null) {
            throw new BusinessException(ResultCode.SPEC_DOC_TYPE_INVALID);
        }
        return reviewStatus;
    }

    /**
     * 获取审批通过后的目标状态
     */
    public String resolveApprovedStatus(String currentStatus) {
        var approvedStatus = REVIEW_TO_APPROVED.get(currentStatus);
        if (approvedStatus == null) {
            throw new BusinessException(ResultCode.SPEC_STATUS_NOT_ALLOWED);
        }
        return approvedStatus;
    }

    /**
     * 获取审批驳回后的回退状态
     */
    public String resolveRejectedStatus(String currentStatus) {
        var rejectedStatus = REVIEW_TO_REJECTED.get(currentStatus);
        if (rejectedStatus == null) {
            throw new BusinessException(ResultCode.SPEC_STATUS_NOT_ALLOWED);
        }
        return rejectedStatus;
    }
}
