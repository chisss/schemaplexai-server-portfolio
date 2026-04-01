package com.schemaplexai.model.vo.system;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * RAG 操作日志视图对象
 */
@Data
public class RagOperationLogVO {

    private String id;
    private String operationType;
    private String sourceType;
    private String sourceId;
    private String contextId;
    private String modelConfigId;
    private String modelName;
    private String provider;
    private String collectionName;
    private String status;
    private Integer chunkCount;
    private Integer retrievedCount;
    private Integer vectorDimension;
    private Integer requestChars;
    private Long durationMs;
    private String errorMessage;
    private Map<String, Object> metadata;
    private LocalDateTime createdAt;
}
