package com.schemaplexai.service.ai;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.util.StringUtils;

/**
 * 工具名规范化器
 *
 * <p>用于把平台内部工具编码转换为满足 OpenAI function calling 约束的合法名称。
 */
public final class ToolNameNormalizer {

    public static final String METADATA_ACTUAL_TOOL_NAME = "actualToolName";

    private ToolNameNormalizer() {}

    /**
     * 规范化模型侧可见的工具名。
     */
    public static String normalize(String originalName) {
        if (!StringUtils.hasText(originalName)) {
            return "tool";
        }
        String normalized = originalName.trim().replaceAll("[^a-zA-Z0-9_-]+", "_");
        normalized = normalized.replaceAll("_{2,}", "_");
        normalized = normalized.replaceAll("^[^a-zA-Z_]+", "");
        normalized = normalized.replaceAll("^_+", "");
        normalized = normalized.replaceAll("_+$", "");
        return StringUtils.hasText(normalized) ? normalized : "tool";
    }

    /**
     * 将工具定义规范化为模型可接受的 ToolSpecification。
     */
    public static ToolSpecification normalizeSpecification(ToolSpecification specification) {
        if (specification == null || !StringUtils.hasText(specification.name())) {
            return specification;
        }
        String normalizedName = normalize(specification.name());
        if (normalizedName.equals(specification.name())) {
            return specification;
        }
        return specification.toBuilder()
                .name(normalizedName)
                .addMetadata(METADATA_ACTUAL_TOOL_NAME, specification.name())
                .build();
    }
}
