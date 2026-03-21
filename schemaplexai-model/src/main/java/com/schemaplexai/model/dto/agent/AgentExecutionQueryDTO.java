package com.schemaplexai.model.dto.agent;

import lombok.Data;

/**
 * Agent 执行记录查询 DTO
 */
@Data
public class AgentExecutionQueryDTO {

    /** 当前页码 */
    private Integer page = 1;

    /** 每页条数 */
    private Integer size = 10;

    /** 执行 ID 精确过滤 */
    private String executionId;

    /** 状态过滤: queued/running/completed/failed/stopped */
    private String status;
}
