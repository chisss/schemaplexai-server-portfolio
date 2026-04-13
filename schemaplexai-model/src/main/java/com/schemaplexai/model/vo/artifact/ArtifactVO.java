package com.schemaplexai.model.vo.artifact;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 统一产物视图
 */
@Data
public class ArtifactVO {

    private String id;

    private String specId;

    private String workflowInstanceId;

    private String workspaceId;

    private String name;

    private String title;

    private String artifactType;

    private String format;

    private String mimeType;

    private Integer latestVersion;

    private String sourceNodeId;

    private String contentText;

    private Map<String, Object> metadataJson;

    private List<ArtifactVersionVO> versions;

    private List<ArtifactDeliveryVO> deliveries;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
