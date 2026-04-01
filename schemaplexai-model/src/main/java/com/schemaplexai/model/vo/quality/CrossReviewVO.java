package com.schemaplexai.model.vo.quality;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class CrossReviewVO {
    private String id;
    private String specId;
    private String specName;
    private String taskId;
    private String profileId;
    private String issueType;
    private String modelAId;
    private String modelAName;
    private Map<String, Object> modelAResult;
    private String modelBId;
    private String modelBName;
    private Map<String, Object> modelBResult;
    private Map<String, Object> mergedResult;
    private Map<String, Object> summary;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorMessage;
    private LocalDateTime createdAt;
}
