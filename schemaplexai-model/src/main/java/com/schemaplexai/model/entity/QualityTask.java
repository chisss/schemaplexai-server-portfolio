package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 质量任务实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_quality_task", autoResultMap = true)
public class QualityTask extends BaseEntity {

    private String taskNo;
    private String issueType;
    private String triggerMode;
    private String sourceType;
    private String sourceAgentId;
    private String specId;
    private String taskId;
    private String agentExecutionId;
    private String profileId;
    private String profileCode;
    private String status;
    private Integer progress;
    private Integer totalItems;
    private Integer successItems;
    private Integer failedItems;
    private Integer skippedItems;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> requestPayload;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> resultSummary;

    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
