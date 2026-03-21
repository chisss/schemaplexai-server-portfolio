package com.schemaplexai.model.converter;

import com.schemaplexai.common.enums.McpServerStatusEnum;
import com.schemaplexai.model.dto.mcp.McpServerCreateRequest;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.model.vo.mcp.McpServerVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * MCP Server转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE,
        imports = {McpServerStatusEnum.class})
public interface McpServerConverter {

    @Mapping(target = "createdByName", ignore = true)
    McpServerVO toVO(McpServer entity);

    List<McpServerVO> toVOList(List<McpServer> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "tools", ignore = true)
    @Mapping(target = "status", expression = "java(McpServerStatusEnum.INACTIVE.getCode())")
    @Mapping(target = "lastHealthCheck", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    McpServer fromCreateRequest(McpServerCreateRequest request);
}
