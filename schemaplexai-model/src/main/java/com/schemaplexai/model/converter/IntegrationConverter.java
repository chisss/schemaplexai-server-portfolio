package com.schemaplexai.model.converter;

import com.schemaplexai.model.dto.integration.IntegrationCreateRequest;
import com.schemaplexai.model.entity.Integration;
import com.schemaplexai.model.vo.integration.IntegrationVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 集成配置转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface IntegrationConverter {

    /**
     * Integration -> IntegrationVO
     */
    @Mapping(target = "createdByName", ignore = true)
    IntegrationVO toVO(Integration entity);

    /**
     * Integration列表 -> IntegrationVO列表
     */
    List<IntegrationVO> toVOList(List<Integration> entities);

    /**
     * IntegrationCreateRequest -> Integration
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "webhookSecret", ignore = true)
    @Mapping(target = "status", constant = "active")
    @Mapping(target = "lastSyncAt", ignore = true)
    @Mapping(target = "errorMessage", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    Integration fromCreateRequest(IntegrationCreateRequest request);
}
