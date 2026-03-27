package com.schemaplexai.model.converter;

import com.schemaplexai.model.entity.TenantRuntimePolicy;
import com.schemaplexai.model.vo.system.TenantRuntimePolicyVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * 租户运行时策略转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface TenantRuntimePolicyConverter {

    TenantRuntimePolicyVO toVO(TenantRuntimePolicy policy);
}
