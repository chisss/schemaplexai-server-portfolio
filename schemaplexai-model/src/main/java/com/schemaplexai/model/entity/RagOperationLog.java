package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * RAG 操作日志实体
 */
@Data
@TableName(value = "sf_rag_operation_log", autoResultMap = true)
public class RagOperationLog implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    /** write/query/delete/rebuild */
    private String operationType;

    /** context_item/knowledge_document/agent_output/system */
    private String sourceType;

    private String sourceId;

    private String contextId;

    private String modelConfigId;

    private String modelName;

    private String provider;

    private String collectionName;

    /** success/failed/skipped */
    private String status;

    private Integer chunkCount;

    private Integer retrievedCount;

    private Integer vectorDimension;

    private Integer requestChars;

    private Integer requestTokens;

    private Long durationMs;

    private String errorMessage;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
