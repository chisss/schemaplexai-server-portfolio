package com.schemaplexai.service.config;

import com.schemaplexai.model.dto.system.TenantProfileUpdateRequest;
import com.schemaplexai.model.vo.system.TenantVO;

/**
 * 租户行业模板服务接口
 */
public interface TenantTemplateService {

    /** 更新租户行业配置（行业、场景、能力） */
    TenantVO updateProfile(String tenantId, TenantProfileUpdateRequest request);

    /** 异步触发模板初始化，返回当前 init_status */
    String initializeTemplate(String tenantId);

    /** 查询模板初始化状态 */
    String getInitStatus(String tenantId);
}
