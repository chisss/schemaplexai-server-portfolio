package com.schemaplexai.service.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.AiModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Token 成本估算器
 */
@Component
@RequiredArgsConstructor
public class TokenEstimator {

    private static final long DEFAULT_INPUT_TOKENS = 1200L;
    private static final long DEFAULT_OUTPUT_TOKENS = 800L;

    private final AgentExecutionMapper agentExecutionMapper;
    private final AiModelMapper aiModelMapper;

    public BigDecimal estimateTaskCost(AiModel model, String useCase) {
        if (model == null) {
            return BigDecimal.ZERO;
        }
        TokenAverage tokenAverage = resolveAverage(useCase, model.getTenantId());
        BigDecimal inputPrice = model.getInputPrice() == null ? BigDecimal.ZERO : model.getInputPrice();
        BigDecimal outputPrice = model.getOutputPrice() == null ? BigDecimal.ZERO : model.getOutputPrice();
        return inputPrice.multiply(BigDecimal.valueOf(tokenAverage.avgInputTokens()))
                .add(outputPrice.multiply(BigDecimal.valueOf(tokenAverage.avgOutputTokens())))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
    }

    private TokenAverage resolveAverage(String useCase, String tenantId) {
        List<String> modelIds = aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                        .eq(StringUtils.hasText(tenantId), AiModel::getTenantId, tenantId)
                        .eq(AiModel::getStatus, CommonConstant.STATUS_ACTIVE)
                        .apply(StringUtils.hasText(useCase), "lower(use_case) = lower({0})", useCase))
                .stream()
                .map(AiModel::getId)
                .toList();
        if (CollectionUtils.isEmpty(modelIds)) {
            return new TokenAverage(DEFAULT_INPUT_TOKENS, DEFAULT_OUTPUT_TOKENS);
        }
        LocalDateTime start = LocalDateTime.now().minusDays(30);
        List<AgentExecution> executions = agentExecutionMapper.selectList(new LambdaQueryWrapper<AgentExecution>()
                .in(AgentExecution::getAiModel, modelIds)
                .ge(AgentExecution::getCreatedAt, start)
                .orderByDesc(AgentExecution::getCreatedAt)
                .last("limit 200"));
        if (CollectionUtils.isEmpty(executions)) {
            return new TokenAverage(DEFAULT_INPUT_TOKENS, DEFAULT_OUTPUT_TOKENS);
        }
        long totalInput = executions.stream().mapToLong(item -> item.getTokenInput() == null ? 0L : item.getTokenInput()).sum();
        long totalOutput = executions.stream().mapToLong(item -> item.getTokenOutput() == null ? 0L : item.getTokenOutput()).sum();
        return new TokenAverage(
                Math.max(1L, totalInput / executions.size()),
                Math.max(1L, totalOutput / executions.size())
        );
    }

    private record TokenAverage(long avgInputTokens, long avgOutputTokens) {
    }
}
