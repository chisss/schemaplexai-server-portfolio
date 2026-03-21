package com.schemaplexai.model.vo.workflow;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 评审会话VO
 */
@Data
public class ReviewSessionVO {

    private String id;

    private String specId;

    private String documentType;

    private String owner;

    /** 评审人列表 [{userId, role, status, submittedAt}] */
    private List<Map<String, Object>> reviewers;

    private LocalDateTime deadline;

    private String timeoutStrategy;

    private String status;

    /** 评审意见列表 */
    private List<ReviewCommentVO> comments;

    /** 评审汇总 */
    private ReviewSummaryVO summary;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
