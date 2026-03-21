package com.schemaplexai.service.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.service.ai.provider.ClaudeProvider;
import com.schemaplexai.service.ai.provider.GeminiProvider;
import com.schemaplexai.service.ai.provider.OpenAIProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 模型路由器（策略模式 + 工厂职责）
 *
 * <p>负责将 Agent 配置的模型显示名称（{@code Agent.aiModel} = {@code AiModel.name}）
 * 解析为可执行的 {@link AiModelResolution}：包含对应的 {@link AIProvider} 策略和
 * 从 {@code sf_ai_model} 表加载的运行时 {@link AiModelConfig}。
 *
 * <p>路由规则（按 {@code AiModel.provider} 字段，不区分大小写）：
 * <ul>
 *   <li>claude / anthropic   → {@link ClaudeProvider}</li>
 *   <li>gemini / google      → {@link GeminiProvider}</li>
 *   <li>其他（openai/deepseek/kimi 等）→ {@link OpenAIProvider}（OpenAI 兼容协议）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIModelRouter {

    private final ClaudeProvider claudeProvider;
    private final OpenAIProvider openAIProvider;
    private final GeminiProvider geminiProvider;
    private final AiModelMapper aiModelMapper;

    /**
     * 按模型显示名称解析 Provider 和运行时连接配置
     *
     * @param modelDisplayName Agent.aiModel 中存储的 AiModel 显示名称（如 "Claude 3.5 Sonnet"）
     * @return 包含 AIProvider 和 AiModelConfig 的解析结果
     * @throws BusinessException 若未找到激活状态的模型配置
     */
    public AiModelResolution resolveByModelName(String modelDisplayName) {
        if (!StringUtils.hasText(modelDisplayName)) {
            throw new BusinessException(ResultCode.AGENT_CONFIG_ERROR);
        }

        AiModel aiModel = aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getName, modelDisplayName)
                        .eq(AiModel::getStatus, "active")
                        .last("LIMIT 1")
        );

        if (aiModel == null) {
            log.error("未找到激活的 AI 模型配置: displayName={}", modelDisplayName);
            throw new BusinessException(ResultCode.AGENT_CONFIG_ERROR);
        }

        AiModelConfig config = AiModelConfig.from(aiModel);
        AIProvider provider = selectProvider(config.getProvider());

        log.info("AI 模型解析成功: displayName={}, provider={}, modelId={}",
                modelDisplayName, config.getProvider(), config.getModelId());

        return new AiModelResolution(provider, config);
    }

    /**
     * 按 provider 字段选择对应的 AIProvider 策略实例
     */
    private AIProvider selectProvider(String provider) {
        return switch (provider) {
            case "claude", "anthropic" -> claudeProvider;
            case "gemini", "google" -> geminiProvider;
            // openai / deepseek / kimi / chatgpt / 其他 OpenAI 兼容协议均使用 OpenAIProvider
            default -> openAIProvider;
        };
    }
}
