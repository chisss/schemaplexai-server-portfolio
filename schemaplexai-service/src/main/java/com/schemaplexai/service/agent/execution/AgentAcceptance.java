package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 固定在本次执行上的 JSON 交付断言，使用 RFC 6901 路径。 */
public record AgentAcceptance(Map<String, String> requiredJson) {

    public static final String SNAPSHOT_KEY = "requiredOutputJson";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private static final Set<String> TYPES = Set.of(
            "any", "array", "boolean", "integer", "null", "number", "object", "string");

    public AgentAcceptance {
        if (requiredJson == null || requiredJson.isEmpty() || requiredJson.size() > 32) {
            throw new IllegalArgumentException("验收断言必须包含 1 至 32 个 JSON 路径");
        }
        requiredJson.forEach((path, type) -> {
            if (path == null || path.length() > 240 || path.chars().anyMatch(Character::isISOControl)
                    || (!path.isEmpty() && !path.startsWith("/")) || path.matches(".*~(?![01]).*")
                    || !TYPES.contains(type == null ? "" : type)) {
                throw new IllegalArgumentException("无效的 JSON 验收路径或类型");
            }
            JsonPointer.compile(path);
        });
        requiredJson = Map.copyOf(requiredJson);
    }

    public static AgentAcceptance fromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || !snapshot.containsKey(SNAPSHOT_KEY)) {
            return null;
        }
        if (!(snapshot.get(SNAPSHOT_KEY) instanceof Map<?, ?> rules)) {
            throw new IllegalStateException("无法读取执行验收快照");
        }
        Map<String, String> requiredJson = new LinkedHashMap<>();
        rules.forEach((path, type) -> {
            if (!(path instanceof String key) || !(type instanceof String value)) {
                throw new IllegalStateException("无法读取执行验收断言");
            }
            requiredJson.put(key, value);
        });
        return new AgentAcceptance(requiredJson);
    }

    public String prompt() {
        return "交付必须为单个 JSON 值。需满足的 JSON Pointer 与类型：" + requiredJson.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList();
    }

    public Verdict evaluate(String output, boolean degraded) {
        if (output == null || output.isBlank()) {
            return new Verdict(Status.UNVERIFIED, "执行未留下可验收的输出");
        }
        JsonNode root;
        try {
            root = JSON.readTree(output);
        } catch (JsonProcessingException e) {
            return new Verdict(Status.REJECTED, "交付输出必须为单个合法 JSON 值");
        }
        for (var rule : requiredJson.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            JsonNode value = root.at(rule.getKey());
            if (!matches(value, rule.getValue())) {
                String path = rule.getKey().isEmpty() ? "根节点" : rule.getKey();
                return new Verdict(Status.REJECTED, "JSON 验收未通过，" + path + " 要求类型 " + rule.getValue());
            }
        }
        return degraded ? new Verdict(Status.PARTIAL, "已通过结构验收，执行以降级结果交付")
                : new Verdict(Status.ACCEPTED, null);
    }

    private static boolean matches(JsonNode value, String type) {
        if (value.isMissingNode()) {
            return false;
        }
        return switch (type) {
            case "any" -> true;
            case "integer" -> value.isIntegralNumber();
            default -> value.getNodeType().name().toLowerCase(Locale.ROOT).equals(type);
        };
    }

    public enum Status {
        ACCEPTED,
        PARTIAL,
        UNVERIFIED,
        REJECTED;

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Verdict(Status status, String explanation) {
        public boolean passed() {
            return status == Status.ACCEPTED || status == Status.PARTIAL;
        }
    }
}
