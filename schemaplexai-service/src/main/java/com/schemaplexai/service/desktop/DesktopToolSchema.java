package com.schemaplexai.service.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;

import java.util.List;
import java.util.Map;

/** 与桌面客户端接收的本机工具参数保持一致。 */
public final class DesktopToolSchema {

    private static final Map<String, String> DESCRIPTIONS = Map.of(
            "workingDirectory", "授权工作区内的相对工作目录，默认为 .",
            "path", "相对于工作目录的路径",
            "content", "文件的完整文本内容",
            "pattern", "在一个目录内搜索文件。通过 path 或 workingDirectory 选择子目录。"
                    + "sys.glob 使用文件名通配符（如 *.java），sys.grep 使用字面文本。两者均只搜索当前这一层。",
            "command", "在授权工作目录执行的完整命令"
    );

    private DesktopToolSchema() {
    }

    public static JsonNode forCode(String toolCode, ObjectMapper mapper) {
        return forCode(toolCode, mapper, false, false);
    }

    public static JsonNode forCode(String toolCode, ObjectMapper mapper, boolean supportsExactEdit,
                                   boolean supportsReadRange) {
        List<String> required = switch (toolCode) {
            case "sys.read", "sys.stat", "sys.mkdir" -> List.of("path");
            case "sys.write" -> List.of("path", "content");
            case "sys.edit" -> supportsExactEdit ? List.of("path") : List.of("path", "content");
            case "sys.glob", "sys.grep" -> List.of("pattern");
            case "sys.bash" -> List.of("command");
            case "sys.ls" -> List.of();
            default -> null;
        };
        if (required == null) {
            return null;
        }

        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var properties = schema.putObject("properties");
        var requiredFields = schema.putArray("required");
        for (String field : required) {
            properties.putObject(field).put("type", "string").put("description", DESCRIPTIONS.get(field));
            requiredFields.add(field);
        }
        if ("sys.edit".equals(toolCode) && supportsExactEdit) {
            properties.putObject("content").put("type", "string").put("description", "完整文件文本，不能与 oldText/newText 同时提供");
            properties.putObject("oldText").put("type", "string").put("description", "文件中恰好出现一次的非空原文，必须同时提供 newText");
            properties.putObject("newText").put("type", "string").put("description", "替换文本，允许空字符串，必须同时提供 oldText");
        }
        if (List.of("sys.ls", "sys.glob", "sys.grep").contains(toolCode)) {
            properties.putObject("path").put("type", "string").put("description", DESCRIPTIONS.get("path"));
        }
        if ("sys.read".equals(toolCode) && supportsReadRange) {
            properties.putObject("startLine").put("type", "integer").put("minimum", 1)
                    .put("description", "片段起始行，从 1 开始，默认第一行");
            properties.putObject("endLine").put("type", "integer").put("minimum", 1)
                    .put("description", "片段结束行，含该行，默认文件末尾。两项均省略时返回完整文本");
        }
        properties.putObject("workingDirectory").put("type", "string")
                .put("description", DESCRIPTIONS.get("workingDirectory"));
        return schema;
    }

    public static boolean hasReadRange(JsonNode arguments) {
        return arguments.has("startLine") || arguments.has("endLine");
    }

    static void validateReadRange(JsonNode arguments) {
        JsonNode start = arguments.get("startLine");
        JsonNode end = arguments.get("endLine");
        if (!validLine(start) || !validLine(end) || (start != null && end != null && start.asLong() > end.asLong())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件读取行号必须为正整数，结束行不能小于起始行");
        }
    }

    private static boolean validLine(JsonNode node) {
        return node == null || (node.isIntegralNumber() && node.canConvertToLong() && node.asLong() > 0);
    }
}
