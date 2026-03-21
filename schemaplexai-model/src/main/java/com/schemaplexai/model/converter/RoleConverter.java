package com.schemaplexai.model.converter;

import com.schemaplexai.common.constants.CommonConstants;
import com.schemaplexai.model.dto.system.RoleCreateRequest;
import com.schemaplexai.model.entity.Role;
import com.schemaplexai.model.vo.system.RoleVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 角色实体转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE,
        imports = {CommonConstants.class})
public interface RoleConverter {

    /**
     * Role -> RoleVO（permissions 需要额外设置）
     */
    @Mapping(target = "permissions", ignore = true)
    RoleVO toVO(Role role);

    List<RoleVO> toVOList(List<Role> roles);

    /**
     * RoleCreateRequest -> Role
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "isSystem", expression = "java(false)")
    @Mapping(target = "status", expression = "java(CommonConstants.STATUS_ACTIVE)")
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    Role fromCreateRequest(RoleCreateRequest request);
}
