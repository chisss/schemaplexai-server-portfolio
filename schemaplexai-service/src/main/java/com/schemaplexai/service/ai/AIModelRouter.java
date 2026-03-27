package com.schemaplexai.service.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AiModelGroupItemMapper;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AiModelGroupItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

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
     * 按模型显示名称解析 ChatModel 和运行时连接配置
     *
     * @param modelDisplayName Agent.aiModel 中存储的 AiModel 显示名称（如 "Claude 3.5 Sonnet"）
     * @return 包含 ChatModel 和 AiModelConfig 的解析结果
     * @throws BusinessException 若未找到激活状态的模型配置
     */
    public LangChain4jResolution resolveByModelName(String modelDisplayName) {
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

        AiModelConfig config = AiModelConfig.from(aiModel);
        log.info("AI 模型解析成功: displayName={}, provider={}, modelId={}",
                modelDisplayName, config.getProvider(), config.getModelId());

        return new LangChain4jResolution(modelFactory.getOrCreate(config), config);
    }

    /**
     * 从模型组按 sort_order 顺序降级解析，所有模型均失败则抛异常
     */
    public LangChain4jResolution resolveFromGroup(String groupId) {
        List<AiModelGroupItem> items = groupItemMapper.selectList(
                new LambdaQueryWrapper<AiModelGroupItem>()
                        .eq(AiModelGroupItem::getGroupId, groupId)
                        .orderByAsc(AiModelGroupItem::getSortOrder));

        if (CollectionUtils.isEmpty(items)) {
            log.error("模型组内无成员: groupId={}", groupId);
            throw new BusinessException(ResultCode.MODEL_GROUP_ALL_UNAVAILABLE);
        }

        for (AiModelGroupItem item : items) {
            try {
                AiModel model = aiModelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getId, item.getModelId())
                        .eq(AiModel::getStatus, CommonConstant.STATUS_ACTIVE));
                if (model == null) {
                    log.warn("模型组降级：模型不可用（inactive或不存在）: groupId={}, modelId={}", groupId, item.getModelId());
                    continue;
                }
                AiModelConfig config = AiModelConfig.from(model);
                log.info("模型组解析成功: groupId={}, modelId={}, sortOrder={}, provider={}",
                        groupId, item.getModelId(), item.getSortOrder(), config.getProvider());
                return new LangChain4jResolution(modelFactory.getOrCreate(config), config);
            } catch (Exception e) {
                log.warn("模型组降级，跳过: groupId={}, modelId={}, error={}", groupId, item.getModelId(), e.getMessage());
            }
        }

        log.error("模型组内所有模型均不可用: groupId={}", groupId);
        throw new BusinessException(ResultCode.MODEL_GROUP_ALL_UNAVAILABLE);
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
}
