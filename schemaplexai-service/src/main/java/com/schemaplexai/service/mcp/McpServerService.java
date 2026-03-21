package com.schemaplexai.service.mcp;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.mcp.McpServerCreateRequest;
import com.schemaplexai.model.dto.mcp.McpServerQueryRequest;
import com.schemaplexai.model.dto.mcp.McpServerUpdateRequest;
import com.schemaplexai.model.vo.mcp.McpServerVO;

/**
 * MCP Server服务接口
 */
public interface McpServerService {

    McpServerVO create(McpServerCreateRequest request);

    PageResult<McpServerVO> page(McpServerQueryRequest request);

    McpServerVO getById(String id);

    McpServerVO update(String id, McpServerUpdateRequest request);

    void delete(String id);

    McpServerVO healthCheck(String id);

    McpServerVO discoverTools(String id);
}
