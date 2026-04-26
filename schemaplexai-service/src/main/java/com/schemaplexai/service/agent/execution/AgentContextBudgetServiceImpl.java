package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.model.vo.agent.AgentContextBudgetLayerVO;
import com.schemaplexai.model.vo.agent.AgentContextBudgetSnapshotVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 上下文预算服务实现
 */
@Service
@RequiredArgsConstructor
public class AgentContextBudgetServiceImpl implements AgentContextBudgetService {

    private static final long DEFAULT_MAX_TOKEN_BUDGET = 200_000L;

    private final ObjectMapper objectMapper;

    @Override
    public AgentContextBudgetSnapshotVO buildSnapshot(AgentExecutionContext context, SandboxPolicy sandboxPolicy) {
        List<AgentContextBudgetLayerVO> layers = new ArrayList<>();
        layers.add(layer("inputPrompt", context != null ? context.getInputPrompt() : null));
        layers.add(layer("inputContext", context != null ? context.getInputContext() : null));
        layers.add(layer("additionalSystemContexts", context != null ? context.getAdditionalSystemContexts() : null));
        layers.add(layer("teamContext", buildTeamContextText(context)));
        long estimatedTokens = layers.stream()
                .map(AgentContextBudgetLayerVO::getEstimatedTokens)
                .filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
        long maxBudget = sandboxPolicy != null && sandboxPolicy.getMaxTokenBudget() > 0
                ? sandboxPolicy.getMaxTokenBudget()
                : DEFAULT_MAX_TOKEN_BUDGET;
        AgentContextBudgetSnapshotVO snapshot = new AgentContextBudgetSnapshotVO();
        snapshot.setModel(context != null ? context.getModel() : null);
        snapshot.setEstimatedInputTokens(estimatedTokens);
        snapshot.setMaxTokenBudget(maxBudget);
        snapshot.setOverBudget(estimatedTokens > maxBudget);
        snapshot.setCompacted(false);
        snapshot.setTokensSavedByCompaction(0L);
        snapshot.setLayers(layers);
        return snapshot;
    }

    private AgentContextBudgetLayerVO layer(String name, Object value) {
        String text = stringify(value);
        AgentContextBudgetLayerVO layer = new AgentContextBudgetLayerVO();
        layer.setName(name);
        layer.setChars(text.length());
        layer.setEstimatedTokens(estimateTokens(text));
        return layer;
    }

    private String buildTeamContextText(AgentExecutionContext context) {
        if (context == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        addIfText(parts, context.getTeamAgentId());
        addIfText(parts, context.getTeamMemberId());
        addIfText(parts, context.getTeamMemberRoleName());
        addIfText(parts, context.getTeamMemberRoleType());
        return String.join("\n", parts);
    }

    private void addIfText(List<String> parts, String value) {
        if (StringUtils.hasText(value)) {
            parts.add(value);
        }
    }

    private long estimateTokens(String text) {
        if (!StringUtils.hasText(text)) {
            return 0L;
        }
        return (long) Math.ceil(text.length() / 4.0D);
    }

    private String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof List<?> list && CollectionUtils.isEmpty(list)) {
            return "";
        }
        if (value instanceof Map<?, ?> map && CollectionUtils.isEmpty(map)) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }
}
