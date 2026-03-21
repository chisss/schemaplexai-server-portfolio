package com.schemaplexai.model.vo.workflow;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评审意见VO
 */
@Data
public class ReviewCommentVO {

    private String id;

    private String sessionId;

    private String reviewerId;

    private String level;

    private String category;

    private String content;

    private String location;

    private LocalDateTime createdAt;
}
