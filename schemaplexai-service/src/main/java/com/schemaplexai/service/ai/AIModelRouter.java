package com.schemaplexai.service.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AiModelGroupItemMapper;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.AiModelRouteMapper;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AiModelGroupItem;
import com.schemaplexai.model.entity.AiModelRoute;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * AI 模型路由器
 *
 * <p>将 Agent 配置的模型显示名称或模型组解析为 {@link LangChain4jResolution}。
 * 模型组模式下按 sort_order 逐个尝试，失败则降级到下一个（自动降级策略）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIModelRouter {

    private final LangChain4jModelFactory modelFactory;
    private final AiModelMapper aiModelMapper;
    private final AiModelRouteMapper aiModelRouteMapper;
    private final AiModelGroupItemMapper groupItemMapper;
    private final ModelLoadBalancer modelLoadBalancer;

    /**
     * 根据 Agent 配置解析模型（支持单个模型和模型组）
     */
    public LangChain4jResolution resolveForAgent(Agent agent) {
        if ("model_group".equals(agent.getAiModelType()) && StringUtils.hasText(agent.getAiModelGroupId())) {
            return resolveFromGroup(agent.getAiModelGroupId());
        }
        return resolveByModelName(agent.getAiModel());
    }

    /**
     * 解析 Agent 的候选模型链路
     * model_group: 组内顺序降级
     * model: 按路由规则降级
     */
    public List<LangChain4jResolution> resolveChainForAgent(Agent agent) {
        if ("model_group".equals(agent.getAiModelType()) && StringUtils.hasText(agent.getAiModelGroupId())) {
            return resolveGroupChain(agent.getAiModelGroupId());
        }
        return resolveRouteChain(agent.getAiModel());
    }

    /**
     * 解析单模型的降级链路
     */
    public List<LangChain4jResolution> resolveRouteChain(String modelDisplayName) {
        AiModel primaryModel = requireActiveModelByName(modelDisplayName);
        LinkedHashSet<String> modelIds = new LinkedHashSet<>();
        modelIds.add(primaryModel.getId());

        AiModelRoute route = aiModelRouteMapper.selectOne(new LambdaQueryWrapper<AiModelRoute>()
                .eq(AiModelRoute::getPrimaryModelId, primaryModel.getId())
                .eq(AiModelRoute::getStatus, CommonConstant.STATUS_ACTIVE)
                .orderByAsc(AiModelRoute::getPriority)
                .last("LIMIT 1"));
        if (route != null) {
            if (StringUtils.hasText(route.getSecondaryModelId())) {
                modelIds.add(route.getSecondaryModelId());
            }
            if (StringUtils.hasText(route.getTertiaryModelId())) {
                modelIds.add(route.getTertiaryModelId());
            }
        }

        List<AiModel> orderedModels = new ArrayList<>();
        for (String modelId : modelIds) {
            orderedModels.add(requireActiveModelById(modelId));
        }
        orderedModels = reorderRouteCandidates(orderedModels);

        List<LangChain4jResolution> chain = new ArrayList<>();
        for (AiModel model : orderedModels) {
            chain.add(buildResolution(model));
        }
        return chain;
    }

    /**
     * 按模型显示名称解析 ChatModel 和运行时连接配置
     *
     * @param modelDisplayName Agent.aiModel 中存储的 AiModel 显示名称（如 "Claude 3.5 Sonnet"）
     * @return 包含 ChatModel 和 AiModelConfig 的解析结果
     * @throws BusinessException 若未找到激活状态的模型配置
     */
    public LangChain4jResolution resolveByModelName(String modelDisplayName) {
        return buildResolution(requireActiveModelByName(modelDisplayName));
    }

    /**
     * 从模型组按 sort_order 顺序降级解析，所有模型均失败则抛异常
     */
    public LangChain4jResolution resolveFromGroup(String groupId) {
        List<LangChain4jResolution> chain = resolveGroupChain(groupId);
        if (CollectionUtils.isEmpty(chain)) {
            throw new BusinessException(ResultCode.MODEL_GROUP_ALL_UNAVAILABLE);
        }
        return chain.getFirst();
    }

    /**
     * 从模型组按 sort_order 顺序解析为降级链路
     */
    public List<LangChain4jResolution> resolveGroupChain(String groupId) {
        List<AiModelGroupItem> items = groupItemMapper.selectList(
                new LambdaQueryWrapper<AiModelGroupItem>()
                        .eq(AiModelGroupItem::getGroupId, groupId)
                        .orderByAsc(AiModelGroupItem::getSortOrder));

        if (CollectionUtils.isEmpty(items)) {
            log.error("模型组内无成员: groupId={}", groupId);
            throw new BusinessException(ResultCode.MODEL_GROUP_ALL_UNAVAILABLE);
        }

        List<LangChain4jResolution> chain = new ArrayList<>();
        for (AiModelGroupItem item : items) {
            try {
                AiModel model = requireActiveModelById(item.getModelId());
                chain.add(buildResolution(model));
            } catch (Exception e) {
                log.warn("模型组降级，跳过: groupId={}, modelId={}, error={}", groupId, item.getModelId(), e.getMessage());
            }
        }

        if (CollectionUtils.isEmpty(chain)) {
            log.error("模型组内所有模型均不可用: groupId={}", groupId);
            throw new BusinessException(ResultCode.MODEL_GROUP_ALL_UNAVAILABLE);
        }
        return chain;
    }

    /**
     * 从模型组按负载均衡策略选择一个模型（非降级模式）
     * 适用场景：并发执行时，不同任务分配到不同模型实例
     *
     * @param groupId   模型组ID
     * @param shardKey  分片键（用于hash负载均衡，通常为tenantId+executionId）
     * @return 包含ChatModel和配置的解析结果
     */
    public LangChain4jResolution resolveForLoadBalancing(String groupId, String shardKey) {
        List<AiModelGroupItem> items = groupItemMapper.selectList(
                new LambdaQueryWrapper<AiModelGroupItem>()
                        .eq(AiModelGroupItem::getGroupId, groupId)
                        .orderByAsc(AiModelGroupItem::getSortOrder));

        if (CollectionUtils.isEmpty(items)) {
            log.error("模型组内无成员: groupId={}", groupId);
            throw new BusinessException(ResultCode.MODEL_GROUP_ALL_UNAVAILABLE);
        }

        List<String> modelIds = items.stream()
                .map(AiModelGroupItem::getModelId)
                .toList();

        String selectedModelId = modelLoadBalancer.selectModelId(groupId, modelIds, shardKey);

        AiModel model = aiModelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getId, selectedModelId)
                .eq(AiModel::getStatus, CommonConstant.STATUS_ACTIVE));

        if (model == null) {
            log.warn("负载均衡命中不可用模型，尝试组内回退: groupId={}, modelId={}", groupId, selectedModelId);
            for (String candidateId : modelIds) {
                if (selectedModelId.equals(candidateId)) {
                    continue;
                }
                AiModel fallback = aiModelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getId, candidateId)
                        .eq(AiModel::getStatus, CommonConstant.STATUS_ACTIVE));
                if (fallback != null) {
                    AiModelConfig fallbackConfig = AiModelConfig.from(fallback);
                    log.info("模型组负载均衡回退成功: groupId={}, selectedModelId={}, fallbackModelId={}",
                            groupId, selectedModelId, candidateId);
                    return new LangChain4jResolution(modelFactory.getOrCreate(fallbackConfig), fallbackConfig);
                }
            }
            log.error("模型组负载均衡无可用模型: groupId={}, selectedModelId={}", groupId, selectedModelId);
            throw new BusinessException(ResultCode.MODEL_GROUP_ALL_UNAVAILABLE);
        }

        AiModelConfig config = AiModelConfig.from(model);
        log.info("模型组负载均衡解析成功: groupId={}, selectedModelId={}, shardKey={}, provider={}",
                groupId, selectedModelId, shardKey, config.getProvider());

        return new LangChain4jResolution(modelFactory.getOrCreate(config), config);
    }

    private AiModel requireActiveModelByName(String modelDisplayName) {
        if (!StringUtils.hasText(modelDisplayName)) {
            throw new BusinessException(ResultCode.AGENT_CONFIG_ERROR);
        }
        AiModel aiModel = aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getName, modelDisplayName)
                        .eq(AiModel::getStatus, CommonConstant.STATUS_ACTIVE)
                        .last("LIMIT 1")
        );
        if (aiModel == null) {
            log.error("未找到激活的 AI 模型配置: displayName={}", modelDisplayName);
            throw new BusinessException(ResultCode.AGENT_CONFIG_ERROR);
        }
        return aiModel;
    }

    private AiModel requireActiveModelById(String modelId) {
        AiModel aiModel = aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getId, modelId)
                        .eq(AiModel::getStatus, CommonConstant.STATUS_ACTIVE)
                        .last("LIMIT 1")
        );
        if (aiModel == null) {
            throw new BusinessException(ResultCode.AGENT_CONFIG_ERROR);
        }
        return aiModel;
    }

    private List<AiModel> reorderRouteCandidates(List<AiModel> candidates) {
        if (CollectionUtils.isEmpty(candidates) || candidates.size() <= 1) {
            return candidates;
        }
        AiModel primary = candidates.getFirst();
        if (isConnectivityHealthy(primary) || candidates.stream().skip(1).noneMatch(this::isConnectivityHealthy)) {
            return candidates;
        }

        List<IndexedModel> indexedModels = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            indexedModels.add(new IndexedModel(i, candidates.get(i)));
        }
        indexedModels.sort(Comparator
                .comparingInt((IndexedModel item) -> connectivityPriority(item.model()))
                .thenComparingInt(item -> latencyPriority(item.model()))
                .thenComparingInt(IndexedModel::index));

        List<AiModel> reordered = indexedModels.stream()
                .map(IndexedModel::model)
                .toList();
        log.info("AI 模型路由根据最近连通性结果重排候选顺序: before={}, after={}",
                candidates.stream().map(AiModel::getName).toList(),
                reordered.stream().map(AiModel::getName).toList());
        return reordered;
    }

    private boolean isConnectivityHealthy(AiModel model) {
        return model != null && "success".equalsIgnoreCase(model.getLastTestStatus());
    }

    private int connectivityPriority(AiModel model) {
        if (isConnectivityHealthy(model)) {
            return 0;
        }
        if (model == null || !StringUtils.hasText(model.getLastTestStatus())) {
            return 1;
        }
        return 2;
    }

    private int latencyPriority(AiModel model) {
        if (!isConnectivityHealthy(model) || model == null || model.getLastTestLatency() == null || model.getLastTestLatency() <= 0) {
            return Integer.MAX_VALUE;
        }
        return model.getLastTestLatency();
    }

    private LangChain4jResolution buildResolution(AiModel aiModel) {
        AiModelConfig config = AiModelConfig.from(aiModel);
        log.info("AI 模型解析成功: modelName={}, provider={}, modelId={}",
                aiModel.getName(), config.getProvider(), config.getModelId());
        return new LangChain4jResolution(modelFactory.getOrCreate(config), config);
    }

    private record IndexedModel(int index, AiModel model) {}

    /**
     * 解析租户的压缩/摘要用小模型（优先选择低成本模型）
     */
    public AiModelConfig resolveCompactModel(String tenantId) {
        List<AiModel> models = aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getTenantId, tenantId)
                .eq(AiModel::getStatus, CommonConstant.STATUS_ACTIVE)
                .orderByAsc(AiModel::getCreatedAt));
        // 优先选择名称含 mini/haiku/flash 的小模型
        AiModel compact = models.stream()
                .filter(m -> {
                    String name = m.getName() != null ? m.getName().toLowerCase() : "";
                    return name.contains("mini") || name.contains("haiku") || name.contains("flash");
                })
                .findFirst()
                .orElse(models.isEmpty() ? null : models.get(0));
        return compact != null ? AiModelConfig.from(compact) : null;
    }

    /**
     * 解析租户的主模型
     */
    public AiModelConfig resolvePrimaryModel(String tenantId) {
        AiModel model = aiModelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getTenantId, tenantId)
                .eq(AiModel::getStatus, CommonConstant.STATUS_ACTIVE)
                .orderByAsc(AiModel::getCreatedAt)
                .last("LIMIT 1"));
        return model != null ? AiModelConfig.from(model) : null;
    }

    /**
     * 根据 AiModelConfig 构建 ChatModel 实例
     */
    public dev.langchain4j.model.chat.ChatModel buildChatModel(AiModelConfig config) {
        return modelFactory.getOrCreate(config);
    }
}
