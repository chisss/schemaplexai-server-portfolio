package com.schemaplexai.service.integration.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.IntegrationMapper;
import com.schemaplexai.dao.mapper.IntegrationProjectMapper;
import com.schemaplexai.dao.mapper.WebhookEventMapper;
import com.schemaplexai.model.converter.IntegrationConverter;
import com.schemaplexai.model.converter.WebhookEventConverter;
import com.schemaplexai.model.entity.Integration;
import com.schemaplexai.model.entity.IntegrationProject;
import com.schemaplexai.model.entity.WebhookEvent;
import com.schemaplexai.model.dto.integration.IntegrationCreateRequest;
import com.schemaplexai.model.dto.integration.IntegrationQueryRequest;
import com.schemaplexai.model.dto.integration.IntegrationUpdateRequest;
import com.schemaplexai.model.dto.integration.ProjectImportRequest;
import com.schemaplexai.model.vo.integration.ConnectionTestVO;
import com.schemaplexai.model.vo.integration.IntegrationProjectVO;
import com.schemaplexai.model.vo.integration.IntegrationVO;
import com.schemaplexai.model.vo.integration.WebhookEventVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.integration.IntegrationService;
import com.schemaplexai.service.integration.validator.IntegrationValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 集成配置服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationServiceImpl implements IntegrationService {

    private final IntegrationMapper integrationMapper;
    private final IntegrationProjectMapper integrationProjectMapper;
    private final WebhookEventMapper webhookEventMapper;
    private final IntegrationConverter integrationConverter;
    private final WebhookEventConverter webhookEventConverter;
    private final IntegrationValidator integrationValidator;
    private final EntityValidator entityValidator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IntegrationVO create(IntegrationCreateRequest request) {
        // 校验平台与集成类型匹配
        integrationValidator.validatePlatformMatch(request.getIntegrationType(), request.getPlatform());
        // 校验配置字段
        integrationValidator.validateConfigFields(request.getPlatform(), request.getConfig());

        // 通过converter构建实体
        var integration = integrationConverter.fromCreateRequest(request);
        integration.setTenantId(SecurityUtil.getCurrentTenantId());
        integration.setCreatedBy(SecurityUtil.getCurrentUserId());
        integration.setStatus("active");

        integrationMapper.insert(integration);
        log.info("创建集成配置成功: integrationId={}, name={}, platform={}",
                integration.getId(), integration.getName(), integration.getPlatform());

        var vo = integrationConverter.toVO(integration);
        vo.setConfig(maskConfig(integration.getConfig()));
        return vo;
    }

    @Override
    public PageResult<IntegrationVO> page(IntegrationQueryRequest request) {
        var page = new Page<Integration>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<Integration>();

        // 可选条件过滤
        if (StringUtils.hasText(request.getIntegrationType())) {
            wrapper.eq(Integration::getIntegrationType, request.getIntegrationType());
        }
        if (StringUtils.hasText(request.getPlatform())) {
            wrapper.eq(Integration::getPlatform, request.getPlatform());
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(Integration::getStatus, request.getStatus());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.like(Integration::getName, request.getKeyword());
        }
        wrapper.orderByDesc(Integration::getCreatedAt);

        var result = integrationMapper.selectPage(page, wrapper);
        var voList = integrationConverter.toVOList(result.getRecords());

        // 对每个VO中的config进行脱敏
        for (IntegrationVO vo : voList) {
            vo.setConfig(maskConfig(vo.getConfig()));
        }

        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public IntegrationVO getById(String id) {
        var integration = entityValidator.requireExists(integrationMapper, id, ResultCode.INTEGRATION_NOT_FOUND);
        var vo = integrationConverter.toVO(integration);
        vo.setConfig(maskConfig(integration.getConfig()));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IntegrationVO update(String id, IntegrationUpdateRequest request) {
        var integration = entityValidator.requireExists(integrationMapper, id, ResultCode.INTEGRATION_NOT_FOUND);

        // 更新非空字段
        if (StringUtils.hasText(request.getName())) {
            integration.setName(request.getName());
        }
        if (request.getConfig() != null) {
            // 更新配置前校验字段合法性
            integrationValidator.validateConfigFields(integration.getPlatform(), request.getConfig());
            integration.setConfig(request.getConfig());
        }
        if (StringUtils.hasText(request.getStatus())) {
            integration.setStatus(request.getStatus());
        }
        integration.setUpdatedBy(SecurityUtil.getCurrentUserId());
        integration.setUpdatedAt(LocalDateTime.now());

        integrationMapper.updateById(integration);
        log.info("更新集成配置成功: integrationId={}", id);

        var vo = integrationConverter.toVO(integration);
        vo.setConfig(maskConfig(integration.getConfig()));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        entityValidator.requireExists(integrationMapper, id, ResultCode.INTEGRATION_NOT_FOUND);
        integrationMapper.deleteById(id);
        log.info("删除集成配置成功: integrationId={}", id);
    }

    @Override
    public ConnectionTestVO testConnection(String id) {
        entityValidator.requireExists(integrationMapper, id, ResultCode.INTEGRATION_NOT_FOUND);

        // TODO: 调用对应平台适配器测试连接
        var result = new ConnectionTestVO();
        result.setConnected(false);
        result.setLatencyMs(0L);
        result.setScopes(new ArrayList<>());
        return result;
    }

    @Override
    public Map<String, Object> sync(String id) {
        entityValidator.requireExists(integrationMapper, id, ResultCode.INTEGRATION_NOT_FOUND);

        // TODO: 调用对应平台适配器执行同步
        return new HashMap<>();
    }

    @Override
    public PageResult<WebhookEventVO> pageWebhookEvents(String integrationId, String eventType,
                                                         String status, Integer page, Integer size) {
        var pageParam = new Page<WebhookEvent>(page, size);
        var wrapper = new LambdaQueryWrapper<WebhookEvent>();

        // 可选条件过滤
        if (StringUtils.hasText(integrationId)) {
            wrapper.eq(WebhookEvent::getIntegrationId, integrationId);
        }
        if (StringUtils.hasText(eventType)) {
            wrapper.eq(WebhookEvent::getEventType, eventType);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(WebhookEvent::getStatus, status);
        }
        wrapper.orderByDesc(WebhookEvent::getCreatedAt);

        var result = webhookEventMapper.selectPage(pageParam, wrapper);
        var voList = webhookEventConverter.toVOList(result.getRecords());
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public List<Map<String, Object>> listAvailableProjects(String integrationId) {
        Integration integration = entityValidator.requireExists(integrationMapper, integrationId, ResultCode.INTEGRATION_NOT_FOUND);

        // 模拟平台项目列表（真实场景调用 Git API）
        List<Map<String, Object>> projects = new ArrayList<>();
        String platform = integration.getPlatform();

        // 返回模拟项目列表
        Map<String, Object> p1 = new HashMap<>();
        p1.put("externalProjectId", "risk-system");
        p1.put("externalProjectName", "风控系统");
        p1.put("description", "企业级风险控制系统，包含实时风险评估、规则引擎和合规报告");
        p1.put("language", "Java");
        p1.put("lastUpdated", "2026-03-18");
        projects.add(p1);

        Map<String, Object> p2 = new HashMap<>();
        p2.put("externalProjectId", "payment-gateway");
        p2.put("externalProjectName", "支付网关");
        p2.put("description", "统一支付接入网关，支持多渠道支付和对账");
        p2.put("language", "Java");
        p2.put("lastUpdated", "2026-03-15");
        projects.add(p2);

        log.info("获取集成平台项目列表: integrationId={}, platform={}, count={}", integrationId, platform, projects.size());
        return projects;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IntegrationProjectVO importProject(String integrationId, ProjectImportRequest request) {
        Integration integration = entityValidator.requireExists(integrationMapper, integrationId, ResultCode.INTEGRATION_NOT_FOUND);

        // 检查是否已导入
        long existCount = integrationProjectMapper.selectCount(
                new LambdaQueryWrapper<IntegrationProject>()
                        .eq(IntegrationProject::getIntegrationId, integrationId)
                        .eq(IntegrationProject::getExternalProjectId, request.getExternalProjectId())
                        .ne(IntegrationProject::getDeleted, 1));
        if (existCount > 0) {
            // 幂等：已导入则返回已有记录
            IntegrationProject existing = integrationProjectMapper.selectOne(
                    new LambdaQueryWrapper<IntegrationProject>()
                            .eq(IntegrationProject::getIntegrationId, integrationId)
                            .eq(IntegrationProject::getExternalProjectId, request.getExternalProjectId())
                            .last("LIMIT 1"));
            log.info("项目已导入，返回已有记录: integrationId={}, externalProjectId={}", integrationId, request.getExternalProjectId());
            return toProjectVO(existing);
        }

        IntegrationProject project = new IntegrationProject();
        project.setTenantId(integration.getTenantId());
        project.setIntegrationId(integrationId);
        project.setExternalProjectId(request.getExternalProjectId());
        project.setExternalProjectName(request.getExternalProjectName());
        project.setSyncConfig(request.getSyncConfig());
        project.setStatus("active");
        project.setCreatedBy(SecurityUtil.getCurrentUserId());
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());

        integrationProjectMapper.insert(project);

        log.info("导入项目成功: integrationId={}, projectId={}, externalName={}",
                integrationId, project.getId(), request.getExternalProjectName());
        return toProjectVO(project);
    }

    @Override
    public List<IntegrationProjectVO> listImportedProjects(String integrationId) {
        entityValidator.requireExists(integrationMapper, integrationId, ResultCode.INTEGRATION_NOT_FOUND);

        List<IntegrationProject> projects = integrationProjectMapper.selectList(
                new LambdaQueryWrapper<IntegrationProject>()
                        .eq(IntegrationProject::getIntegrationId, integrationId)
                        .orderByDesc(IntegrationProject::getCreatedAt));

        return projects.stream().map(this::toProjectVO).collect(java.util.stream.Collectors.toList());
    }

    private IntegrationProjectVO toProjectVO(IntegrationProject project) {
        IntegrationProjectVO vo = new IntegrationProjectVO();
        vo.setId(project.getId());
        vo.setIntegrationId(project.getIntegrationId());
        vo.setProjectId(project.getProjectId());
        vo.setExternalProjectId(project.getExternalProjectId());
        vo.setExternalProjectName(project.getExternalProjectName());
        vo.setSyncConfig(project.getSyncConfig());
        vo.setStatus(project.getStatus());
        vo.setLastSyncAt(project.getLastSyncAt());
        return vo;
    }

    // ======================== 私有方法 ========================

    /**
     * 对配置信息进行脱敏处理
     * 将key中包含 secret/token/password/key（不区分大小写）的值替换为 "***"
     *
     * @param config 原始配置
     * @return 脱敏后的配置副本
     */
    private Map<String, Object> maskConfig(Map<String, Object> config) {
        if (config == null) {
            return null;
        }
        var masked = new HashMap<String, Object>(config.size());
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String key = entry.getKey();
            String lowerKey = key.toLowerCase();
            if (lowerKey.contains("secret") || lowerKey.contains("token")
                    || lowerKey.contains("password") || lowerKey.contains("api_key")
                    || lowerKey.contains("apikey") || lowerKey.contains("access_key")
                    || lowerKey.contains("private_key")) {
                masked.put(key, "***");
            } else {
                masked.put(key, entry.getValue());
            }
        }
        return masked;
    }
}
