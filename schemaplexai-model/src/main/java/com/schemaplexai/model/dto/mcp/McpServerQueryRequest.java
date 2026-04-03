package com.schemaplexai.model.dto.mcp;

import lombok.Data;

/**
 * MCP Server查询请求
 */
@Data
public class McpServerQueryRequest {

    /** 当前页码 */
    private Integer page = 1;

    /** 每页大小 */
    private Integer size = 20;

    /** 状态 */
    private String status;

    /** 服务类型 */
    private String serverType;

    /** 关键字搜索 */
    private String keyword;
}
