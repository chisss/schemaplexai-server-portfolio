package com.schemaplexai.model.vo.context;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上下文快照VO
 */
@Data
public class ContextSnapshotVO {

    private String id;

    private String contextId;

    private String snapshotName;

    private Integer itemCount;

    private Integer totalTokens;

    private LocalDateTime createdAt;
}
