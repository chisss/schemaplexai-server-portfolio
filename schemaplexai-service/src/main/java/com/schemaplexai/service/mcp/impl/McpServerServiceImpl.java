package com.schemaplexai.service.mcp.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.enums.McpServerStatusEnum;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.McpServerMapper;
import com.schemaplexai.model.converter.McpServerConverter;
import com.schemaplexai.model.dto.mcp.McpServerCreateRequest;
import com.schemaplexai.model.dto.mcp.McpServerQueryRequest;
import com.schemaplexai.model.dto.mcp.McpServerUpdateRequest;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.model.vo.mcp.McpServerVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.integration.mcp.McpClientService;
import com.schemaplexai.service.mcp.McpServerService;
import com.schemaplexai.service.mcp.validator.McpServerValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Server服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpServerServiceImpl implements McpServerService {

    private final McpServerMapper mcpServerMapper;
    private final McpServerConverter mcpServerConverter;
    private final McpServerValidator mcpServerValidator;
    private final EntityValidator entityValidator;
    private final McpClientService mcpClientService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpServerVO create(McpServerCreateRequest request) {
        mcpServerValidator.validateNameUnique(request.getName());

        var mcpServer = mcpServerConverter.fromCreateRequest(request);
        mcpServerMapper.insert(mcpServer);
        log.info("注册MCP Server成功: mcpServerId={}, name={}", mcpServer.getId(), mcpServer.getName());

        var vo = mcpServerConverter.toVO(mcpServer);
        vo.setAuthConfig(maskConfig(mcpServer.getAuthConfig()));
        return vo;
    }

    @Override
    public PageResult<McpServerVO> page(McpServerQueryRequest request) {
        var page = new Page<McpServer>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<McpServer>();

        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(McpServer::getStatus, request.getStatus());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(w -> w.like(McpServer::getName, request.getKeyword())
                    .or().like(McpServer::getUrl, request.getKeyword()));
        }
        wrapper.orderByDesc(McpServer::getCreatedAt);

        var result = mcpServerMapper.selectPage(page, wrapper);
        var voList = mcpServerConverter.toVOList(result.getRecords());
        for (McpServerVO vo : voList) {
            vo.setAuthConfig(maskConfig(vo.getAuthConfig()));
        }
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public McpServerVO getById(String id) {
        var mcpServer = entityValidator.requireExists(mcpServerMapper, id, ResultCode.MCP_SERVER_NOT_FOUND);
        var vo = mcpServerConverter.toVO(mcpServer);
        vo.setAuthConfig(maskConfig(mcpServer.getAuthConfig()));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public McpServerVO update(String id, McpServerUpdateRequest request) {
        var mcpServer = entityValidator.requireExists(mcpServerMapper, id, ResultCode.MCP_SERVER_NOT_FOUND);

        if (StringUtils.hasText(request.getName())) {
            mcpServer.setName(request.getName());
        }
        if (StringUtils.hasText(request.getUrl())) {
            mcpServer.setUrl(request.getUrl());
        }
        if (request.getAuthType() != null) {
            mcpServer.setAuthType(request.getAuthType());
        }
        if (request.getAuthConfig() != null) {
            mcpServer.setAuthConfig(request.getAuthConfig());
        }
        if (StringUtils.hasText(request.getStatus())) {
            mcpServer.setStatus(request.getStatus());
        }

        mcpServerMapper.updateById(mcpServer);
        log.info("更新MCP Server成功: mcpServerId={}", id);

        var vo = mcpServerConverter.toVO(mcpServer);
        vo.setAuthConfig(maskConfig(mcpServer.getAuthConfig()));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        entityValidator.requireExists(mcpServerMapper, id, ResultCode.MCP_SERVER_NOT_FOUND);
        mcpServerMapper.deleteById(id);
        log.info("删除MCP Server成功: mcpServerId={}", id);
    }

    @Override
    public McpServerVO healthCheck(String id) {
        var mcpServer = entityValidator.requireExists(mcpServerMapper, id, ResultCode.MCP_SERVER_NOT_FOUND);

        // 调用 MCP 协议的 initialize 请求进行健康检查
        boolean healthy = mcpClientService.healthCheck(
                mcpServer.getUrl(), mcpServer.getAuthType(), mcpServer.getAuthConfig());

        mcpServer.setLastHealthCheck(LocalDateTime.now());
        if (healthy) {
            mcpServer.setStatus(McpServerStatusEnum.ACTIVE.getCode());
            log.info("MCP Server健康检查通过: mcpServerId={}", id);
        } else {
            mcpServer.setStatus(McpServerStatusEnum.ERROR.getCode());
            log.warn("MCP Server健康检查失败: mcpServerId={}, url={}", id, mcpServer.getUrl());
        }
        mcpServerMapper.updateById(mcpServer);

        var vo = mcpServerConverter.toVO(mcpServer);
        vo.setAuthConfig(maskConfig(mcpServer.getAuthConfig()));
        return vo;
    }

    @Override
    @SuppressWarnings("unchecked")
    public McpServerVO discoverTools(String id) {
        var mcpServer = entityValidator.requireExists(mcpServerMapper, id, ResultCode.MCP_SERVER_NOT_FOUND);

        // 调用 MCP 协议的 tools/list 请求发现工具
        List<Map<String, Object>> tools = mcpClientService.discoverTools(
                mcpServer.getUrl(), mcpServer.getAuthType(), mcpServer.getAuthConfig());

        mcpServer.setTools(new java.util.ArrayList<>(tools));
        mcpServerMapper.updateById(mcpServer);
        log.info("MCP Server工具发现完成: mcpServerId={}, toolCount={}", id, tools.size());

        var vo = mcpServerConverter.toVO(mcpServer);
        vo.setAuthConfig(maskConfig(mcpServer.getAuthConfig()));
        return vo;
    }

    /**
     * 认证配置脱敏
     */
    private Map<String, Object> maskConfig(Map<String, Object> config) {
        if (config == null) {
            return null;
        }
        var masked = new HashMap<String, Object>(config.size());
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String lowerKey = entry.getKey().toLowerCase();
            if (lowerKey.contains("secret") || lowerKey.contains("token")
                    || lowerKey.contains("password") || lowerKey.contains("api_key")
                    || lowerKey.contains("apikey") || lowerKey.contains("private_key")) {
                masked.put(entry.getKey(), "***");
            } else {
                masked.put(entry.getKey(), entry.getValue());
            }
        }
        return masked;
    }
}
