package com.schemaplexai.model.vo.artifact;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 产物版本视图
 */
@Data
public class ArtifactVersionVO {

    private String id;

    private Integer versionNumber;

    private String contentText;

    private Map<String, Object> metadataJson;

    private LocalDateTime createdAt;

    private String createdBy;
}
