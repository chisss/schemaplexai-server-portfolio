package com.schemaplexai.service.config;

import com.schemaplexai.model.dto.system.TenantRuntimePolicyUpdateRequest;
import com.schemaplexai.model.vo.system.TenantRuntimePolicyVO;

/**
 * 租户运行时策略服务
 */
public interface TenantRuntimePolicyService {

    TenantRuntimePolicyVO getByTenantId(String tenantId);

    TenantRuntimePolicyVO update(String tenantId, TenantRuntimePolicyUpdateRequest request);
}
