package com.schemaplexai.model.vo.artifact;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 产物投递记录视图
 */
@Data
public class ArtifactDeliveryVO {

    private String id;

    private String workspaceId;

    private String deliveryType;

    private String targetPath;

    private String targetUri;

    private String deliveryStatus;

    private String message;

    private LocalDateTime deliveredAt;

    private Map<String, Object> metadataJson;

    private LocalDateTime createdAt;
}
