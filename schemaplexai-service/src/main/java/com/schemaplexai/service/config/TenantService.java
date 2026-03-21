package com.schemaplexai.service.config;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.system.TenantCreateRequest;
import com.schemaplexai.model.dto.system.TenantUpdateRequest;
import com.schemaplexai.model.vo.system.TenantVO;

/**
 * 租户管理服务接口
 */
public interface TenantService {

    PageResult<TenantVO> listTenants(int page, int size, String keyword);

    TenantVO getTenantById(String id);

    TenantVO createTenant(TenantCreateRequest request);

    TenantVO updateTenant(String id, TenantUpdateRequest request);

    void deleteTenant(String id);

    void updateStatus(String id, String status);
}
