package com.schemaplexai.model.vo.agent;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 执行日志 VO
 */
@Data
public class AgentExecutionLogVO {

    private String logId;

    private String logLevel;

    private String logType;

    private Integer roundNum;

    private String toolName;

    private String content;

    /** 日志概要，列表模式优先展示 */
    private String summary;

    private Integer tokenDelta;

    private Integer elapsedMs;

    private LocalDateTime createdAt;
}
