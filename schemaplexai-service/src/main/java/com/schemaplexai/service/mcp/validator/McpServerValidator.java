package com.schemaplexai.service.mcp.validator;

import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.McpServerMapper;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.service.common.EntityValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * MCP Server业务校验器
 */
@Component
@RequiredArgsConstructor
public class McpServerValidator {

    private final McpServerMapper mcpServerMapper;
    private final EntityValidator entityValidator;

    /**
     * 校验名称唯一性
     */
    public void validateNameUnique(String name) {
        entityValidator.checkUnique(mcpServerMapper, McpServer::getName, name, ResultCode.MCP_SERVER_NAME_DUPLICATE);
    }
}
