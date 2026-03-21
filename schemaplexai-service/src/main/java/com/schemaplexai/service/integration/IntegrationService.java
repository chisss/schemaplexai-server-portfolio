package com.schemaplexai.service.integration;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.integration.IntegrationCreateRequest;
import com.schemaplexai.model.dto.integration.IntegrationQueryRequest;
import com.schemaplexai.model.dto.integration.IntegrationUpdateRequest;
import com.schemaplexai.model.dto.integration.ProjectImportRequest;
import com.schemaplexai.model.vo.integration.ConnectionTestVO;
import com.schemaplexai.model.vo.integration.IntegrationProjectVO;
import com.schemaplexai.model.vo.integration.IntegrationVO;
import com.schemaplexai.model.vo.integration.WebhookEventVO;

import java.util.List;
import java.util.Map;

/**
 * 集成配置服务
 */
public interface IntegrationService {

    IntegrationVO create(IntegrationCreateRequest request);

    PageResult<IntegrationVO> page(IntegrationQueryRequest request);

    IntegrationVO getById(String id);

    IntegrationVO update(String id, IntegrationUpdateRequest request);

    void delete(String id);

    ConnectionTestVO testConnection(String id);

    Map<String, Object> sync(String id);

    PageResult<WebhookEventVO> pageWebhookEvents(String integrationId, String eventType, String status, Integer page, Integer size);

    /**
     * 列出集成平台上可用的项目列表（模拟）
     */
    List<Map<String, Object>> listAvailableProjects(String integrationId);

    /**
     * 从集成平台导入项目，创建工作空间关联
     */
    IntegrationProjectVO importProject(String integrationId, ProjectImportRequest request);

    /**
     * 获取已导入的集成项目列表
     */
    List<IntegrationProjectVO> listImportedProjects(String integrationId);
}
