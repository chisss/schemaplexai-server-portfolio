package com.schemaplexai.service.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.enums.BrainstormDirectionEnum;
import com.schemaplexai.common.enums.BrainstormThemeEnum;
import com.schemaplexai.service.agent.tool.ToolConstants;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.common.model.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 思维导图生成 Skill 执行器
 * 生成 Mermaid 格式的思维导图
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrainstormSkillExecutor implements BuiltinSkillExecutor {

    private final ObjectMapper objectMapper;

    @Override
    public String getSkillCode() {
        return ToolConstants.SKILL_CODE_BRAINSTORM;
    }

    @Override
    public ToolResult execute(String tenantId, String agentId, ToolCall toolCall, Map<String, Object> config) {
        try {
            Map<String, Object> args = extractArguments(toolCall);

            String topic = sanitizeText(String.valueOf(args.getOrDefault("topic", "Main Topic")));
            var branches = args.get("branches");

            String themeStr = config != null
                    ? sanitizeText(String.valueOf(config.getOrDefault("theme", ToolConstants.DEFAULT_BRAINSTORM_THEME)))
                    : ToolConstants.DEFAULT_BRAINSTORM_THEME;
            String directionStr = config != null
                    ? sanitizeText(String.valueOf(config.getOrDefault("direction", ToolConstants.DEFAULT_BRAINSTORM_DIRECTION)))
                    : ToolConstants.DEFAULT_BRAINSTORM_DIRECTION;

            BrainstormThemeEnum theme = BrainstormThemeEnum.fromCode(themeStr);
            BrainstormDirectionEnum direction = BrainstormDirectionEnum.fromCode(directionStr);

            String mermaidCode = generateMermaidMindmap(topic, branches, theme.getCode(), direction.getCode());

            Map<String, Object> result = Map.of(
                    "skillCode", ToolConstants.SKILL_CODE_BRAINSTORM,
                    "topic", topic,
                    "theme", theme.getCode(),
                    "direction", direction.getCode(),
                    "mermaidCode", mermaidCode,
                    "status", "generated",
                    "message", "思维导图生成成功"
            );

            log.info("思维导图生成完成: tenantId={}, agentId={}, topic={}", tenantId, agentId, topic);

            return success(toolCall, result);
        } catch (Exception e) {
            log.error("思维导图生成失败: tenantId={}, agentId={}, error={}", tenantId, agentId, e.getMessage(), e);
            return failure(toolCall, "思维导图生成失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String generateMermaidMindmap(String topic, Object branches, String theme, String direction) {
        StringBuilder sb = new StringBuilder();
        sb.append("mindmap\n");
        sb.append("  root((").append(sanitizeText(topic)).append("))\n");

        if (branches instanceof Iterable<?> iterable) {
            for (Object branch : iterable) {
                if (branch instanceof Map) {
                    Map<String, Object> branchMap = (Map<String, Object>) branch;
                    String branchName = sanitizeText(String.valueOf(branchMap.getOrDefault("name", "Branch")));
                    sb.append("    ").append(branchName).append("\n");

                    Object subBranches = branchMap.get("children");
                    if (subBranches instanceof Iterable<?> subIterable) {
                        for (Object sub : subIterable) {
                            if (sub instanceof Map) {
                                Map<String, Object> subMap = (Map<String, Object>) sub;
                                String subName = sanitizeText(String.valueOf(subMap.getOrDefault("name", "Sub")));
                                sb.append("      ").append(subName).append("\n");
                            } else {
                                sb.append("      ").append(sanitizeText(String.valueOf(sub))).append("\n");
                            }
                        }
                    }
                } else {
                    sb.append("    ").append(sanitizeText(String.valueOf(branch))).append("\n");
                }
            }
        }

        return sb.toString();
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
                     .replace("[<>\"'&]", "")
                     .trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractArguments(ToolCall toolCall) {
        if (toolCall == null || toolCall.getArguments() == null) {
            return Map.of();
        }
        return objectMapper.convertValue(toolCall.getArguments(), Map.class);
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
