package com.schemaplexai.service.config;

import com.schemaplexai.model.dto.system.AiModelCreateRequest;
import com.schemaplexai.model.dto.system.AiModelRouteCreateRequest;
import com.schemaplexai.model.dto.system.AiModelRouteUpdateRequest;
import com.schemaplexai.model.dto.system.AiModelUpdateRequest;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.TeamTemplate;
import com.schemaplexai.model.vo.system.AiModelRouteVO;
import com.schemaplexai.model.vo.system.ConnectivityTestResultVO;

import java.util.List;

/**
 * 系统配置服务接口 - AI模型配置管理
 */
public interface SystemConfigService {

    /**
     * 获取所有可用AI模型列表（仅active）
     */
    List<AiModel> listAiModels();

    /**
     * 获取AI模型详情
     */
    AiModel getAiModelById(String id);

    /**
     * 列出所有模型（含inactive）
     */
    List<AiModel> listAllAiModels();

    /**
     * 创建AI模型
     */
    AiModel createAiModel(AiModelCreateRequest request);

    /**
     * 更新AI模型
     */
    AiModel updateAiModel(String id, AiModelUpdateRequest request);

    /**
     * 删除AI模型
     */
    void deleteAiModel(String id);

    /**
     * 获取路由规则列表
     */
    List<AiModelRouteVO> listRoutes();

    /**
     * 获取路由规则详情
     */
    AiModelRouteVO getRouteById(String id);

    /**
     * 创建路由规则
     */
    AiModelRouteVO createRoute(AiModelRouteCreateRequest request);

    /**
     * 更新路由规则
     */
    AiModelRouteVO updateRoute(String id, AiModelRouteUpdateRequest request);

    /**
     * 删除路由规则
     */
    void deleteRoute(String id);

    /**
     * 获取团队模板列表
     */
    List<TeamTemplate> listTeamTemplates();

    /**
     * 按code获取团队模板
     */
    TeamTemplate getTeamTemplateByCode(String code);

    /**
     * 测试 AI 模型连通性
     */
    ConnectivityTestResultVO testConnectivity(String modelId);
}
