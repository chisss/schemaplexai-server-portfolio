package com.schemaplexai.service.workflow.validator;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.ReviewCommentMapper;
import com.schemaplexai.model.entity.ReviewComment;
import com.schemaplexai.model.entity.ReviewSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 评审业务校验器
 */
@Component
@RequiredArgsConstructor
public class ReviewValidator {

    private final ReviewCommentMapper reviewCommentMapper;

    /**
     * 校验当前用户是评审人
     */
    @SuppressWarnings("unchecked")
    public void validateIsReviewer(ReviewSession session) {
        var currentUserId = SecurityUtil.getCurrentUserId();
        var reviewers = session.getReviewers();
        if (reviewers == null) {
            throw new BusinessException(ResultCode.WORKFLOW_NOT_REVIEWER);
        }

        boolean isReviewer = reviewers.stream()
                .filter(r -> r instanceof Map)
                .map(r -> (Map<String, Object>) r)
                .anyMatch(r -> currentUserId.equals(r.get("userId")));

        if (!isReviewer) {
            throw new BusinessException(ResultCode.WORKFLOW_NOT_REVIEWER);
        }
    }

    /**
     * 校验当前用户未重复提交评审
     */
    public void validateNotDuplicate(String sessionId) {
        var currentUserId = SecurityUtil.getCurrentUserId();
        var count = reviewCommentMapper.selectCount(
                new LambdaQueryWrapper<ReviewComment>()
                        .eq(ReviewComment::getSessionId, sessionId)
                        .eq(ReviewComment::getReviewerId, currentUserId)
        );
        if (count > 0) {
            throw new BusinessException(ResultCode.REVIEW_ALREADY_SUBMITTED);
        }
    }
}
