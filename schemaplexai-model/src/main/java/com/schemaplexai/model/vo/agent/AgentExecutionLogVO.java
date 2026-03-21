package com.schemaplexai.model.vo.agent;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 执行日志 VO
 */
@Data
public class AgentExecutionLogVO {

    private String logLevel;

    private String logType;

    private Integer roundNum;

    private String toolName;

    private String content;

    private Integer tokenDelta;

    private Integer elapsedMs;

    private LocalDateTime createdAt;
}
