package com.schemaplexai.model.vo.quality;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DeviationVO {
    private String id;
    private String workspaceId;
    private String projectName;
    private String specId;
    private String specName;
    private String deviationType;
    private String deviationTypeLabel;
    private String severity;
    private String severityLabel;
    private String title;
    private String description;
    private String expectedValue;
    private String actualValue;
    private String filePath;
    private Integer lineNumber;
    private String status;
    private String statusLabel;
    private String resolvedByName;
    private LocalDateTime resolvedAt;
    private String sourceType;
    private String taskId;
    private String agentExecutionId;
    private LocalDateTime createdAt;
}
