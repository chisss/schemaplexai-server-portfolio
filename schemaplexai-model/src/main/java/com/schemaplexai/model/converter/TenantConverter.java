package com.schemaplexai.model.converter;

import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.model.dto.system.TenantCreateRequest;
import com.schemaplexai.model.entity.Tenant;
import com.schemaplexai.model.vo.system.TenantVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 租户实体转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE,
        imports = {CommonConstant.class})
public interface TenantConverter {

    TenantVO toVO(Tenant tenant);

    List<TenantVO> toVOList(List<Tenant> tenants);

    /**
     * TenantCreateRequest -> Tenant
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", expression = "java(CommonConstant.STATUS_ACTIVE)")
    @Mapping(target = "config", ignore = true)
    @Mapping(target = "industry", ignore = true)
    @Mapping(target = "scenarios", ignore = true)
    @Mapping(target = "enabledCapabilities", ignore = true)
    @Mapping(target = "initStatus", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Tenant fromCreateRequest(TenantCreateRequest request);
}
