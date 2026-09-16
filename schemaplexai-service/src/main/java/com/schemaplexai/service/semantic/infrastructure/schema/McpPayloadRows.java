package com.schemaplexai.service.semantic.infrastructure.schema;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将常见 MCP 工具返回结构收敛为元数据行。 */
final class McpPayloadRows {

    private static final List<String> CONTAINER_KEYS = List.of(
            "rows", "data", "result", "results", "collections", "fields", "indexes", "documents", "content");

    private final ObjectMapper objectMapper;

    McpPayloadRows(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<Map<String, Object>> extract(Map<String, Object> payload) {
        List<Map<String, Object>> rows = extractValue(payload);
        return rows == null ? List.of() : rows;
    }

    private List<Map<String, Object>> extractValue(Object value) {
        if (value instanceof List<?> list) {
            return fromList(list);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = normalizeMap(map);
            for (String key : CONTAINER_KEYS) {
                Object nested = findIgnoreCase(normalized, key);
                List<Map<String, Object>> rows = extractValue(nested);
                if (rows != null) {
                    return rows;
                }
            }
            return List.of(normalized);
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return parseText(text);
        }
        return null;
    }

    private List<Map<String, Object>> fromList(List<?> list) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> normalized = normalizeMap(map);
                Object text = findIgnoreCase(normalized, "text");
                Object type = findIgnoreCase(normalized, "type");
                if (text instanceof String textValue && type != null
                        && "text".equalsIgnoreCase(String.valueOf(type))) {
                    List<Map<String, Object>> parsed = parseText(textValue);
                    if (parsed != null) {
                        rows.addAll(parsed);
                    }
                    continue;
                }
                rows.add(normalized);
            } else if (item != null) {
                rows.add(Map.of("name", String.valueOf(item)));
            }
        }
        return rows;
    }

    private List<Map<String, Object>> parseText(String text) {
        try {
            Object parsed = objectMapper.readValue(text, new TypeReference<>() { });
            return extractValue(parsed);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> normalizeMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }

    private Object findIgnoreCase(Map<String, Object> source, String key) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
