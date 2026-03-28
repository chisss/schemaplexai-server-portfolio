package com.schemaplexai.service.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.enums.CssStyleEnum;
import com.schemaplexai.common.enums.FrontendFrameworkEnum;
import com.schemaplexai.service.agent.tool.ToolConstants;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.common.model.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 前端设计生成 Skill 执行器
 * 根据描述生成 React/Vue 组件代码
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FrontendDesignSkillExecutor implements BuiltinSkillExecutor {

    private final ObjectMapper objectMapper;

    @Override
    public String getSkillCode() {
        return ToolConstants.SKILL_CODE_FRONTEND_DESIGN;
    }

    @Override
    public ToolResult execute(String tenantId, String agentId, ToolCall toolCall, Map<String, Object> config) {
        try {
            Map<String, Object> args = extractArguments(toolCall);

            String description = sanitizeText(String.valueOf(args.getOrDefault("description", "")));
            if (description.isEmpty()) {
                return failure(toolCall, "缺少必填参数: description");
            }

            String frameworkStr = config != null
                    ? sanitizeText(String.valueOf(config.getOrDefault("framework", ToolConstants.DEFAULT_FRAMEWORK)))
                    : ToolConstants.DEFAULT_FRAMEWORK;
            String cssStyleStr = config != null
                    ? sanitizeText(String.valueOf(config.getOrDefault("css_style", ToolConstants.DEFAULT_CSS_STYLE)))
                    : ToolConstants.DEFAULT_CSS_STYLE;

            FrontendFrameworkEnum framework = FrontendFrameworkEnum.fromCode(frameworkStr);
            CssStyleEnum cssStyle = CssStyleEnum.fromCode(cssStyleStr);

            String generatedCode = generateComponentCode(description, framework, cssStyle);

            Map<String, Object> result = Map.of(
                    "skillCode", ToolConstants.SKILL_CODE_FRONTEND_DESIGN,
                    "description", description,
                    "framework", framework.getCode(),
                    "cssStyle", cssStyle.getCode(),
                    "generatedCode", generatedCode,
                    "status", "generated",
                    "message", "前端组件生成成功"
            );

            log.info("前端设计生成完成: tenantId={}, agentId={}, framework={}", tenantId, agentId, framework.getCode());

            return success(toolCall, result);
        } catch (Exception e) {
            log.error("前端设计生成失败: tenantId={}, agentId={}, error={}", tenantId, agentId, e.getMessage(), e);
            return failure(toolCall, "前端设计生成失败: " + e.getMessage());
        }
    }

    private String generateComponentCode(String description, FrontendFrameworkEnum framework, CssStyleEnum cssStyle) {
        if (framework == null || framework == FrontendFrameworkEnum.REACT) {
            return generateReactComponent(description, cssStyle);
        }
        return switch (framework) {
            case VUE -> generateVueComponent(description, cssStyle);
            case VANILLA -> generateVanillaComponent(description, cssStyle);
            default -> generateReactComponent(description, cssStyle);
        };
    }

    private String generateReactComponent(String description, CssStyleEnum cssStyle) {
        String containerClass = cssStyle == CssStyleEnum.TAILWIND ? "p-4" : "container";
        return """
                import React from 'react';

                /**
                 * Auto-generated component
                 * Description: %s
                 */
                export const GeneratedComponent = () => {
                  return (
                    <div className="%s">
                      <h2>Generated Component</h2>
                      <p>%s</p>
                    </div>
                  );
                };
                """.formatted(description, containerClass, description);
    }

    private String generateVueComponent(String description, CssStyleEnum cssStyle) {
        String containerClass = cssStyle == CssStyleEnum.TAILWIND ? "p-4" : "container";
        return """
                <template>
                  <div :class="containerClass">
                    <h2>Generated Component</h2>
                    <p>{{ description }}</p>
                  </div>
                </template>

                <script setup>
                defineProps({
                  description: {
                    type: String,
                    default: '%s'
                  }
                });

                const containerClass = '%s';
                </script>
                """.formatted(description, containerClass);
    }

    private String generateVanillaComponent(String description, CssStyleEnum cssStyle) {
        String cssClass = cssStyle.getCode();
        return """
                <!-- Auto-generated component -->
                <!-- Description: %s -->
                <div class="%s">
                  <h2>Generated Component</h2>
                  <p>%s</p>
                </div>

                <style>
                .%s {
                  padding: 1rem;
                }
                </style>
                """.formatted(description, cssClass, description, cssClass);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractArguments(ToolCall toolCall) {
        if (toolCall == null || toolCall.getArguments() == null) {
            return Map.of();
        }
        return objectMapper.convertValue(toolCall.getArguments(), Map.class);
    }

    private String sanitizeText(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("<script", "")
                     .replace(">", "")
                     .replace("</script>", "")
                     .replace("javascript:", "")
                     .replace("onclick=", "")
                     .replace("onerror=", "")
                     .replace("[<>\"']", "")
                     .trim();
    }

    private ToolResult success(ToolCall toolCall, Object payload) {
        return ToolResult.builder()
                .callId(toolCall != null ? toolCall.getCallId() : null)
                .toolCode(toolCall != null ? toolCall.getToolCode() : null)
                .success(true)
                .result(objectMapper.valueToTree(payload))
                .build();
    }

    private ToolResult failure(ToolCall toolCall, String message) {
        return ToolResult.builder()
                .callId(toolCall != null ? toolCall.getCallId() : null)
                .toolCode(toolCall != null ? toolCall.getToolCode() : null)
                .success(false)
                .result(objectMapper.nullNode())
                .errorMessage(message)
                .build();
    }
}
