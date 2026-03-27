package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sf_tool_execution_log")
public class ToolExecutionLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String agentId;
    private String sessionId;
    private String toolCallId;
    private String toolType;
    private String toolName;
    private String provider;
    private String status;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Long latencyMs;
    private String requestPayload;
    private String responsePayload;
    private String errorCode;
    private String errorMessage;
    private String traceId;
    private String createdBy;
    private LocalDateTime createdAt;
}
