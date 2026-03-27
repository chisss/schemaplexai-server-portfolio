package com.schemaplexai.service.config.impl;

import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.TenantMapper;
import com.schemaplexai.dao.mapper.TenantRuntimePolicyMapper;
import com.schemaplexai.model.converter.TenantRuntimePolicyConverter;
import com.schemaplexai.model.dto.system.TenantRuntimePolicyUpdateRequest;
import com.schemaplexai.model.entity.TenantRuntimePolicy;
import com.schemaplexai.model.vo.system.TenantRuntimePolicyVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.config.TenantRuntimePolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 租户运行时策略服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantRuntimePolicyServiceImpl implements TenantRuntimePolicyService {

    private static final String DEFAULT_WORKSPACE_ROOT_PATH = "/data/workspaces";
    private static final int DEFAULT_MAX_WORKSPACE_GB = 50;
    private static final int DEFAULT_MAX_EXECUTION_MINUTES = 30;
    private static final String DEFAULT_SANDBOX_PROFILE = "standard";

    private final TenantMapper tenantMapper;
    private final TenantRuntimePolicyMapper tenantRuntimePolicyMapper;
    private final TenantRuntimePolicyConverter tenantRuntimePolicyConverter;
    private final EntityValidator entityValidator;

    @Override
    public TenantRuntimePolicyVO getByTenantId(String tenantId) {
        entityValidator.requireExists(tenantMapper, tenantId, ResultCode.TENANT_NOT_FOUND);
        TenantRuntimePolicy policy = tenantRuntimePolicyMapper.selectById(tenantId);
        if (policy == null) {
            policy = buildDefaultPolicy(tenantId);
        }
        return tenantRuntimePolicyConverter.toVO(policy);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantRuntimePolicyVO update(String tenantId, TenantRuntimePolicyUpdateRequest request) {
        entityValidator.requireExists(tenantMapper, tenantId, ResultCode.TENANT_NOT_FOUND);

        TenantRuntimePolicy policy = tenantRuntimePolicyMapper.selectById(tenantId);
        if (policy == null) {
            policy = buildDefaultPolicy(tenantId);
            applyRequest(policy, request);
            tenantRuntimePolicyMapper.insert(policy);
        } else {
            applyRequest(policy, request);
            tenantRuntimePolicyMapper.updateById(policy);
        }

        log.info("更新租户运行时策略成功: tenantId={}", tenantId);
        return tenantRuntimePolicyConverter.toVO(tenantRuntimePolicyMapper.selectById(tenantId));
    }

    private TenantRuntimePolicy buildDefaultPolicy(String tenantId) {
        TenantRuntimePolicy policy = new TenantRuntimePolicy();
        policy.setTenantId(tenantId);
        policy.setWorkspaceRootPath(DEFAULT_WORKSPACE_ROOT_PATH);
        policy.setMaxWorkspaceGb(DEFAULT_MAX_WORKSPACE_GB);
        policy.setMaxExecutionMinutes(DEFAULT_MAX_EXECUTION_MINUTES);
        policy.setSandboxProfile(DEFAULT_SANDBOX_PROFILE);
        policy.setAllowLocalImport(Boolean.FALSE);
        return policy;
    }

    private void applyRequest(TenantRuntimePolicy policy, TenantRuntimePolicyUpdateRequest request) {
        if (request == null) {
            return;
        }
        if (StringUtils.hasText(request.getWorkspaceRootPath())) {
            policy.setWorkspaceRootPath(request.getWorkspaceRootPath().trim());
        }
        if (request.getMaxWorkspaceGb() != null) {
            policy.setMaxWorkspaceGb(request.getMaxWorkspaceGb());
        }
        if (request.getMaxExecutionMinutes() != null) {
            policy.setMaxExecutionMinutes(request.getMaxExecutionMinutes());
        }
        if (StringUtils.hasText(request.getSandboxProfile())) {
            policy.setSandboxProfile(request.getSandboxProfile().trim());
        }
        if (request.getAllowLocalImport() != null) {
            policy.setAllowLocalImport(request.getAllowLocalImport());
        }
    }
}
