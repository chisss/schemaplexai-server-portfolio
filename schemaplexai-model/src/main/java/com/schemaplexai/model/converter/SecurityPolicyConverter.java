package com.schemaplexai.model.converter;

import com.schemaplexai.model.dto.security.SecurityPolicySaveRequest;
import com.schemaplexai.model.entity.SecurityPolicy;
import com.schemaplexai.model.vo.security.SecurityPolicyVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 安全策略转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface SecurityPolicyConverter {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "isBuiltin", ignore = true)
    @Mapping(target = "lastPublishedAt", ignore = true)
    SecurityPolicy fromRequest(SecurityPolicySaveRequest request);

    SecurityPolicyVO toVO(SecurityPolicy entity);

    List<SecurityPolicyVO> toVOList(List<SecurityPolicy> entities);
}
