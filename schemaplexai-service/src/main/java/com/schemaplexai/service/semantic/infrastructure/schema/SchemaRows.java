package com.schemaplexai.service.semantic.infrastructure.schema;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 扫描适配器内部的行读取帮助类。 */
final class SchemaRows {

    private SchemaRows() {
    }

    static String text(Map<String, Object> row, String... keys) {
        Object value = value(row, keys);
        return value == null || !StringUtils.hasText(String.valueOf(value))
                ? null
                : String.valueOf(value).trim();
    }

    static boolean bool(Map<String, Object> row, String... keys) {
        Object value = value(row, keys);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return value != null && SetValues.TRUE.contains(String.valueOf(value).trim().toLowerCase(Locale.ROOT));
    }

    static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    static List<String> strings(Map<String, Object> row, String... keys) {
        Object value = value(row, keys);
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item != null && StringUtils.hasText(String.valueOf(item)))
                    .map(item -> String.valueOf(item).trim())
                    .toList();
        }
        String text = value == null ? null : String.valueOf(value);
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        return List.of(text.split(",")).stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    static Map<String, Long> distribution(Map<String, Object> row) {
        Object value = value(row, "type_distribution", "typeDistribution", "types");
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        source.forEach((key, count) -> {
            if (key != null && StringUtils.hasText(String.valueOf(key))) {
                result.put(String.valueOf(key).trim(), number(count));
            }
        });
        return result;
    }

    static List<Map<String, Object>> matching(
            List<Map<String, Object>> rows,
            String schema,
            String objectName) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String rowSchema = text(row, "object_schema", "table_schema", "schema_name", "schema");
            String rowObject = text(row, "object_name", "table_name", "collection_name", "name");
            if (equalsNullable(schema, rowSchema) && objectName.equals(rowObject)) {
                result.add(row);
            }
        }
        return result;
    }

    static boolean included(String name, java.util.Set<String> included) {
        return included == null || included.isEmpty() || included.contains(name);
    }

    private static Object value(Map<String, Object> row, String... keys) {
        if (row == null) {
            return null;
        }
        for (String key : keys) {
            if (row.containsKey(key)) {
                return row.get(key);
            }
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static boolean equalsNullable(String expected, String actual) {
        return expected == null || expected.equals(actual);
    }

    private static final class SetValues {
        private static final java.util.Set<String> TRUE = java.util.Set.of("true", "yes", "y", "1", "unique");
    }
}
