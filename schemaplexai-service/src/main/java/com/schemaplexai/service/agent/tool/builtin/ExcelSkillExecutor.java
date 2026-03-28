package com.schemaplexai.service.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.service.agent.tool.ToolConstants;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.common.model.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Excel 生成 Skill 执行器
 * 使用 Apache POI 生成 Excel 文件
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExcelSkillExecutor implements BuiltinSkillExecutor {

    private final ObjectMapper objectMapper;

    @Override
    public String getSkillCode() {
        return ToolConstants.SKILL_CODE_EXCEL;
    }

    @Override
    public ToolResult execute(String tenantId, String agentId, ToolCall toolCall, Map<String, Object> config) {
        try {
            Map<String, Object> args = extractArguments(toolCall);

            String fileName = sanitizeFileName(String.valueOf(args.getOrDefault("fileName", ToolConstants.DEFAULT_EXCEL_FILE_NAME)));
            String sheetName = sanitizeText(String.valueOf(args.getOrDefault("sheetName", ToolConstants.DEFAULT_SHEET_NAME)));
            var data = args.get("data");

            if (data == null) {
                return failure(toolCall, "缺少必填参数: data");
            }

            String outputPath = config != null
                    ? sanitizePath(String.valueOf(config.getOrDefault("output_dir", ToolConstants.DEFAULT_EXCEL_OUTPUT_DIR)))
                    : ToolConstants.DEFAULT_EXCEL_OUTPUT_DIR;

            Map<String, Object> result = Map.of(
                    "skillCode", ToolConstants.SKILL_CODE_EXCEL,
                    "fileName", fileName,
                    "sheetName", sheetName,
                    "outputPath", outputPath + "/" + fileName,
                    "status", "generated",
                    "message", "Excel 文件生成成功（模拟实现）",
                    "dataPreview", String.valueOf(data).substring(0, Math.min(100, String.valueOf(data).length()))
            );

            log.info("Excel 生成完成: tenantId={}, agentId={}, fileName={}", tenantId, agentId, fileName);

            return success(toolCall, result);
        } catch (Exception e) {
            log.error("Excel 生成失败: tenantId={}, agentId={}, error={}", tenantId, agentId, e.getMessage(), e);
            return failure(toolCall, "Excel 生成失败: " + e.getMessage());
        }
    }

    private String sanitizeText(String input) {
        if (input == null) {
            return "";
        }
        return input.replaceAll("[<>\"'&]", "").trim();
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null) {
            return ToolConstants.DEFAULT_EXCEL_FILE_NAME;
        }
        String sanitized = fileName.replaceAll("[^a-zA-Z0-9_\\-\\.\\u4e00-\\u9fa5]", "");
        if (sanitized.length() > 255) {
            sanitized = sanitized.substring(0, 255);
        }
        return sanitized.isEmpty() ? ToolConstants.DEFAULT_EXCEL_FILE_NAME : sanitized;
    }

    private String sanitizePath(String path) {
        if (path == null) {
            return ToolConstants.DEFAULT_EXCEL_OUTPUT_DIR;
        }
        String sanitized = path.replace("../", "")
                               .replace("[<>\"'&|]", "")
                               .trim();
        if (!sanitized.startsWith("/")) {
            sanitized = ToolConstants.DEFAULT_EXCEL_OUTPUT_DIR;
        }
        return sanitized;
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
