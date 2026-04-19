package com.schemaplexai.service.integration.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.exception.BusinessException;
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
import com.schemaplexai.model.vo.integration.IntegrationRepositoryTreeNodeVO;
import com.schemaplexai.model.vo.integration.IntegrationRepositoryVO;
import com.schemaplexai.model.vo.integration.IntegrationVO;
import com.schemaplexai.model.vo.integration.WebhookEventVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.integration.IntegrationService;
import com.schemaplexai.service.integration.platform.ConnectionTestResult;
import com.schemaplexai.service.integration.platform.GitPlatformAdapter;
import com.schemaplexai.service.integration.platform.GitPlatformAdapterFactory;
import com.schemaplexai.service.integration.platform.RemoteProject;
import com.schemaplexai.service.integration.platform.RemoteRepositoryTreeNode;
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
import java.util.Objects;

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
    private final GitPlatformAdapterFactory gitPlatformAdapterFactory;

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
        integration.setStatus(CommonConstant.STATUS_ACTIVE);

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
        var integration = requireAccessibleIntegration(id);
        var vo = integrationConverter.toVO(integration);
        vo.setConfig(maskConfig(integration.getConfig()));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IntegrationVO update(String id, IntegrationUpdateRequest request) {
        var integration = requireAccessibleIntegration(id);

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
        requireAccessibleIntegration(id);
        integrationMapper.deleteById(id);
        log.info("删除集成配置成功: integrationId={}", id);
    }

    @Override
    public ConnectionTestVO testConnection(String id) {
        Integration integration = requireAccessibleIntegration(id);

        GitPlatformAdapter adapter = gitPlatformAdapterFactory.getAdapter(integration.getPlatform());
        ConnectionTestResult result = adapter.testConnection(integration.getConfig());

        // 更新集成状态
        integration.setLastSyncAt(LocalDateTime.now());
        integration.setErrorMessage(result.isConnected() ? null : result.getErrorMessage());
        integrationMapper.updateById(integration);

        var vo = new ConnectionTestVO();
        vo.setConnected(result.isConnected());
        vo.setLatencyMs(result.getLatencyMs());
        vo.setScopes(result.getScopes() != null ? result.getScopes() : List.of());
        vo.setUsername(result.getUsername());
        vo.setErrorMessage(result.getErrorMessage());
        return vo;
    }

    @Override
    public Map<String, Object> sync(String id) {
        Integration integration = requireAccessibleIntegration(id);

        GitPlatformAdapter adapter = gitPlatformAdapterFactory.getAdapter(integration.getPlatform());
        List<RemoteProject> remoteProjects = adapter.syncProjects(integration.getConfig());

        int created = 0;
        int updated = 0;
        for (RemoteProject rp : remoteProjects) {
            IntegrationProject existing = integrationProjectMapper.selectOne(
                    new LambdaQueryWrapper<IntegrationProject>()
                            .eq(IntegrationProject::getIntegrationId, id)
                            .eq(IntegrationProject::getExternalProjectId, rp.getRemoteId())
                            .last("LIMIT 1"));
            if (existing == null) {
                IntegrationProject project = new IntegrationProject();
                project.setIntegrationId(id);
                project.setTenantId(integration.getTenantId());
                project.setExternalProjectId(rp.getRemoteId());
                project.setExternalProjectName(rp.getFullName());
                integrationProjectMapper.insert(project);
                created++;
            } else {
                existing.setExternalProjectName(rp.getFullName());
                integrationProjectMapper.updateById(existing);
                updated++;
            }
        }

        integration.setLastSyncAt(LocalDateTime.now());
        integration.setErrorMessage(null);
        integrationMapper.updateById(integration);

        return Map.of("total", remoteProjects.size(), "created", created, "updated", updated);
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
        Integration integration = requireAccessibleIntegration(integrationId);

        GitPlatformAdapter adapter = gitPlatformAdapterFactory.getAdapter(integration.getPlatform());
        List<RemoteProject> remoteProjects = adapter.syncProjects(integration.getConfig());

        List<Map<String, Object>> projects = new ArrayList<>();
        for (RemoteProject rp : remoteProjects) {
            Map<String, Object> item = new HashMap<>();
            item.put("externalProjectId", rp.getRemoteId());
            item.put("externalProjectName", rp.getFullName());
            item.put("description", rp.getDescription());
            item.put("defaultBranch", rp.getDefaultBranch());
            item.put("httpUrl", rp.getHttpUrl());
            projects.add(item);
        }

        log.info("获取集成平台项目列表: integrationId={}, platform={}, count={}",
                integrationId, integration.getPlatform(), projects.size());
        return projects;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IntegrationProjectVO importProject(String integrationId, ProjectImportRequest request) {
        Integration integration = requireAccessibleIntegration(integrationId);

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
        project.setStatus(CommonConstant.STATUS_ACTIVE);
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
        requireAccessibleIntegration(integrationId);

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
        vo.setLocalPath(project.getLocalPath());
        return vo;
    }

    @Override
    public List<IntegrationProjectVO> listAllImportedProjects() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        List<IntegrationProject> projects = integrationProjectMapper.selectList(
                new LambdaQueryWrapper<IntegrationProject>()
                        .eq(IntegrationProject::getTenantId, tenantId)
                        .orderByDesc(IntegrationProject::getCreatedAt));
        return projects.stream().map(this::toProjectVO).collect(java.util.stream.Collectors.toList());
    }

    @Override
    public List<IntegrationRepositoryVO> listRepositories(String integrationId) {
        Integration integration = requireAccessibleIntegration(integrationId);

        GitPlatformAdapter adapter = gitPlatformAdapterFactory.getAdapter(integration.getPlatform());
        return adapter.listRepositories(integration.getConfig()).stream()
                .map(this::toRepositoryVO)
                .toList();
    }

    @Override
    public List<IntegrationRepositoryTreeNodeVO> listRepositoryTree(String integrationId, String repositoryId, String ref, String path) {
        if (!StringUtils.hasText(repositoryId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仓库ID不能为空");
        }
        Integration integration = requireAccessibleIntegration(integrationId);
        GitPlatformAdapter adapter = gitPlatformAdapterFactory.getAdapter(integration.getPlatform());
        String normalizedPath = !StringUtils.hasText(path) ? "/" : path.trim();
        try {
            return adapter.getRepositoryTree(integration.getConfig(), repositoryId, ref, normalizedPath).stream()
                    .map(this::toRepositoryTreeNodeVO)
                    .toList();
        } catch (UnsupportedOperationException ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST, ex.getMessage());
        }
    }

    // ======================== 私有方法 ========================

    private Integration requireAccessibleIntegration(String id) {
        Integration integration = entityValidator.requireExists(integrationMapper, id, ResultCode.INTEGRATION_NOT_FOUND);
        String currentTenantId = SecurityUtil.getCurrentTenantId();
        if (StringUtils.hasText(currentTenantId) && !Objects.equals(currentTenantId, integration.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该集成配置");
        }
        return integration;
    }

    private IntegrationRepositoryVO toRepositoryVO(RemoteProject project) {
        IntegrationRepositoryVO vo = new IntegrationRepositoryVO();
        vo.setId(project.getRemoteId());
        vo.setName(project.getName());
        vo.setFullName(project.getFullName());
        vo.setDescription(project.getDescription());
        vo.setDefaultBranch(project.getDefaultBranch());
        vo.setHttpUrl(project.getHttpUrl());
        vo.setSshUrl(project.getSshUrl());
        return vo;
    }

    private IntegrationRepositoryTreeNodeVO toRepositoryTreeNodeVO(RemoteRepositoryTreeNode node) {
        IntegrationRepositoryTreeNodeVO vo = new IntegrationRepositoryTreeNodeVO();
        vo.setPath(node.getPath());
        vo.setName(node.getName());
        vo.setType(node.getType());
        vo.setLeaf(node.isLeaf());
        return vo;
    }

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
