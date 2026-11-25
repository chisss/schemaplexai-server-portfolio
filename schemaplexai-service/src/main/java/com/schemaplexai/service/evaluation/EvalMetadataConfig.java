package com.schemaplexai.service.evaluation;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 数据集条目 metadata 中的评估配置解析（第三批：评估配置下放）
 * <p>
 * 支持键：
 * <ul>
 *   <li>systemPrompt（String）：条目级自定义系统提示词，覆盖默认评估提示词</li>
 *   <li>context（String 或 List）：RAG 场景的检索上下文，注入评估输入</li>
 * </ul>
 * 未知键忽略，保持前向兼容。
 * </p>
 */
public record EvalMetadataConfig(String systemPrompt, List<String> contexts) {

    /** 空配置（metadata 缺失或未配置） */
    public static final EvalMetadataConfig EMPTY = new EvalMetadataConfig(null, List.of());

    /**
     * 从数据集条目 metadata 解析评估配置
     */
    public static EvalMetadataConfig from(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return EMPTY;
        }
        String systemPrompt = null;
        Object systemPromptValue = metadata.get("systemPrompt");
        if (systemPromptValue instanceof String value && StringUtils.hasText(value)) {
            systemPrompt = value;
        }
        List<String> contexts = List.of();
        Object contextValue = metadata.get("context");
        if (contextValue instanceof String value && StringUtils.hasText(value)) {
            contexts = List.of(value);
        } else if (contextValue instanceof List<?> list) {
            contexts = list.stream()
                    .filter(item -> item instanceof String && StringUtils.hasText((String) item))
                    .map(String.class::cast)
                    .toList();
        }
        return new EvalMetadataConfig(systemPrompt, contexts);
    }

    public boolean hasSystemPrompt() {
        return StringUtils.hasText(systemPrompt);
    }

    public boolean hasContext() {
        return contexts != null && !contexts.isEmpty();
    }

    /**
     * 把上下文渲染为 QA 模式用户消息前缀
     */
    public String renderContextBlock() {
        if (!hasContext()) {
            return null;
        }
        StringBuilder builder = new StringBuilder("【检索上下文】\n");
        for (String context : contexts) {
            builder.append(context).append("\n\n");
        }
        return builder.toString().trim();
    }
}
