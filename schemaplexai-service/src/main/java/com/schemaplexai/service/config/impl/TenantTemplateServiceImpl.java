package com.schemaplexai.service.config.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.TenantMapper;
import com.schemaplexai.model.converter.TenantConverter;
import com.schemaplexai.model.dto.system.TenantProfileUpdateRequest;
import com.schemaplexai.model.entity.Tenant;
import com.schemaplexai.model.vo.system.TenantVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.config.TenantTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/**
 * 租户行业模板服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantTemplateServiceImpl implements TenantTemplateService {

    private final TenantMapper tenantMapper;
    private final TenantConverter tenantConverter;
    private final EntityValidator entityValidator;
    private final JdbcTemplate jdbcTemplate;

    private static final Map<String, String> INDUSTRY_TEMPLATE_FILES = Map.of(
            "tech", "sql-templates/template_tech.sql",
            "finance", "sql-templates/template_finance.sql",
            "retail", "sql-templates/template_retail.sql",
            "healthcare", "sql-templates/template_healthcare.sql",
            "manufacturing", "sql-templates/template_manufacturing.sql"
    );

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantVO updateProfile(String tenantId, TenantProfileUpdateRequest request) {
        var tenant = entityValidator.requireExists(tenantMapper, tenantId, ResultCode.TENANT_NOT_FOUND);

        if (StringUtils.hasText(request.getIndustry())) {
            tenant.setIndustry(request.getIndustry());
        }
        if (request.getScenarios() != null) {
            tenant.setScenarios(request.getScenarios());
        }
        if (request.getEnabledCapabilities() != null) {
            tenant.setEnabledCapabilities(request.getEnabledCapabilities());
        }
        tenantMapper.updateById(tenant);
        log.info("更新租户行业配置: tenantId={}, industry={}", tenantId, request.getIndustry());
        return tenantConverter.toVO(tenant);
    }

    @Override
    public String initializeTemplate(String tenantId) {
        var tenant = entityValidator.requireExists(tenantMapper, tenantId, ResultCode.TENANT_NOT_FOUND);

        if ("running".equals(tenant.getInitStatus())) {
            throw new BusinessException(ResultCode.TENANT_TEMPLATE_INIT_RUNNING);
        }

        if (!StringUtils.hasText(tenant.getIndustry())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请先设置行业类型");
        }

        // 标记为 running
        tenantMapper.update(null, new LambdaUpdateWrapper<Tenant>()
                .eq(Tenant::getId, tenantId)
                .set(Tenant::getInitStatus, "running"));

        // 异步执行模板 SQL
        doInitializeAsync(tenantId, tenant.getIndustry());
        return "running";
    }

    @Override
    public String getInitStatus(String tenantId) {
        var tenant = entityValidator.requireExists(tenantMapper, tenantId, ResultCode.TENANT_NOT_FOUND);
        return tenant.getInitStatus() != null ? tenant.getInitStatus() : "pending";
    }

    @Async
    @Transactional(rollbackFor = Exception.class)
    public void doInitializeAsync(String tenantId, String industry) {
        log.info("开始异步执行行业模板初始化: tenantId={}, industry={}", tenantId, industry);
        try {
            String templateFile = INDUSTRY_TEMPLATE_FILES.get(industry);
            if (templateFile == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的行业类型: " + industry);
            }

            String sqlContent = loadTemplateSql(templateFile);
            // 将 :tenant_id 替换为实际租户ID（使用字面量拼接，tenant_id 来自系统内部，非用户输入）
            String resolvedSql = sqlContent.replace(":tenant_id", "'" + tenantId + "'");

            // 按语句分割执行（以分号+换行分割，支持PL/pgSQL）
            String[] statements = resolvedSql.split(";\\s*\\n");
            for (String stmt : statements) {
                String trimmed = stmt.trim();
                if (StringUtils.hasText(trimmed) && !trimmed.startsWith("--")) {
                    jdbcTemplate.execute(trimmed);
                }
            }

            // 更新状态为 done
            tenantMapper.update(null, new LambdaUpdateWrapper<Tenant>()
                    .eq(Tenant::getId, tenantId)
                    .set(Tenant::getInitStatus, "done"));
            log.info("行业模板初始化完成: tenantId={}, industry={}", tenantId, industry);

        } catch (Exception e) {
            log.error("行业模板初始化失败: tenantId={}, industry={}, error={}", tenantId, industry, e.getMessage(), e);
            tenantMapper.update(null, new LambdaUpdateWrapper<Tenant>()
                    .eq(Tenant::getId, tenantId)
                    .set(Tenant::getInitStatus, "failed"));
        }
    }

    private String loadTemplateSql(String resourcePath) throws IOException {
        var resource = new ClassPathResource(resourcePath);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
