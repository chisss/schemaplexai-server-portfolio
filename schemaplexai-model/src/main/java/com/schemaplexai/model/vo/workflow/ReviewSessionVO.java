package com.schemaplexai.model.vo.workflow;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.schemaplexai.model.vo.workflow.ReviewCommentVO;
import com.schemaplexai.model.vo.workflow.ReviewSummaryVO;

/**
 * 评审会话VO
 */
@Data
public class ReviewSessionVO {

    private String id;

    private String specId;

    private String specName;

    private String workflowInstanceId;

    private String workflowNodeId;

    private String workflowNodeLabel;

    private String documentType;

    private String owner;

    private String ownerName;

    /** 评审人列表 [{userId, role, status, submittedAt}] */
    private List<Map<String, Object>> reviewers;

    private LocalDateTime deadline;

    private String timeoutStrategy;

    private String status;

    private String decisionStatus;

    private String reviewActionUrl;

    private String messageTemplateId;

    /** 评审意见列表 */
    private List<ReviewCommentVO> comments;

    /** 评审汇总 */
    private ReviewSummaryVO summary;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
