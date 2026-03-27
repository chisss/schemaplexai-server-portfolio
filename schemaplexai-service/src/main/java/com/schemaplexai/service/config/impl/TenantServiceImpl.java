package com.schemaplexai.service.config.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.TenantMapper;
import com.schemaplexai.model.converter.TenantConverter;
import com.schemaplexai.model.dto.system.TenantCreateRequest;
import com.schemaplexai.model.dto.system.TenantUpdateRequest;
import com.schemaplexai.model.entity.Tenant;
import com.schemaplexai.model.vo.system.TenantVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.config.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * 租户管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantServiceImpl implements TenantService {

    private final TenantMapper tenantMapper;
    private final TenantConverter tenantConverter;
    private final EntityValidator entityValidator;

    @Override
    public PageResult<TenantVO> listTenants(int page, int size, String keyword) {
        var pageParam = new Page<Tenant>(page, size);
        var wrapper = new LambdaQueryWrapper<Tenant>();

        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w
                    .like(Tenant::getName, keyword)
                    .or()
                    .like(Tenant::getCode, keyword)
            );
        }
        wrapper.orderByDesc(Tenant::getCreatedAt);

        var result = tenantMapper.selectPage(pageParam, wrapper);
        var voList = tenantConverter.toVOList(result.getRecords());
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public TenantVO getTenantById(String id) {
        var tenant = entityValidator.requireExists(tenantMapper, id, ResultCode.TENANT_NOT_FOUND);
        return tenantConverter.toVO(tenant);
    }

    @Override
    public TenantVO createTenant(TenantCreateRequest request) {
        entityValidator.checkUnique(tenantMapper, Tenant::getCode, request.getCode(),
                ResultCode.TENANT_CODE_DUPLICATE);

        var tenant = tenantConverter.fromCreateRequest(request);
        tenantMapper.insert(tenant);

        log.info("创建租户成功: tenantId={}, code={}", tenant.getId(), tenant.getCode());
        return tenantConverter.toVO(tenant);
    }

    @Override
    public TenantVO updateTenant(String id, TenantUpdateRequest request) {
        entityValidator.requireExists(tenantMapper, id, ResultCode.TENANT_NOT_FOUND);

        var updateEntity = new Tenant();
        updateEntity.setId(id);
        updateEntity.setName(request.getName());
        updateEntity.setDescription(request.getDescription());
        updateEntity.setLogoUrl(request.getLogoUrl());
        updateEntity.setStatus(request.getStatus());
        tenantMapper.updateById(updateEntity);

        log.info("更新租户成功: tenantId={}", id);
        return getTenantById(id);
    }

    @Override
    public void deleteTenant(String id) {
        entityValidator.requireExists(tenantMapper, id, ResultCode.TENANT_NOT_FOUND);
        tenantMapper.deleteById(id);
        log.info("删除租户成功: tenantId={}", id);
    }

    @Override
    public void updateStatus(String id, String status) {
        if (!Set.of(CommonConstant.STATUS_ACTIVE, CommonConstant.STATUS_INACTIVE).contains(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无效的状态值，允许值: active, inactive");
        }
        entityValidator.requireExists(tenantMapper, id, ResultCode.TENANT_NOT_FOUND);
        var update = new Tenant();
        update.setId(id);
        update.setStatus(status);
        tenantMapper.updateById(update);
        log.info("更新租户状态: tenantId={}, status={}", id, status);
    }
}
