package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 固定在本次执行上的交付断言。摘要值以服务端最终交付的 UTF-8 字节为准。 */
public record AgentAcceptance(Map<String, String> requiredJson, Map<String, JsonNode> requiredValues, String requiredSha256) {
    public static final String SNAPSHOT_KEY = "requiredOutputJson";
    public static final String VALUE_SNAPSHOT_KEY = "requiredOutputValues";
    public static final String SHA_SNAPSHOT_KEY = "requiredOutputSha256";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private static final Set<String> TYPES = Set.of(
            "any", "array", "boolean", "integer", "null", "number", "object", "string");

    public AgentAcceptance(Map<String, String> requiredJson) {
        this(requiredJson, null, null);
    }

    public AgentAcceptance {
        var types = requiredJson == null ? Map.<String, String>of() : requiredJson;
        var values = requiredValues == null ? Map.<String, JsonNode>of() : requiredValues;
        if (types.size() + values.size() > 32 || (types.isEmpty() && values.isEmpty() && requiredSha256 == null)) {
            throw new IllegalArgumentException("验收契约需至少配置一个断言，JSON 断言最多 32 个");
        }
        types.forEach((path, type) -> {
            validatePath(path);
            if (type == null || !TYPES.contains(type)) {
                throw new IllegalArgumentException("无效的 JSON 验收类型");
            }
        });
        Map<String, JsonNode> immutableValues = new LinkedHashMap<>();
        values.forEach((path, value) -> {
            validatePath(path);
            JsonNode normalized = value == null ? NullNode.getInstance() : value;
            if (normalized.toString().length() > 2_000) {
                throw new IllegalArgumentException("单个 JSON 验收值不能超过 2000 字符");
            }
            immutableValues.put(path, normalized.deepCopy());
        });
        if (requiredSha256 != null && !requiredSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("交付摘要必须为 64 位十六进制 SHA-256");
        }
        requiredJson = Map.copyOf(types);
        requiredValues = Collections.unmodifiableMap(immutableValues);
        requiredSha256 = requiredSha256 == null ? null : requiredSha256.toLowerCase(Locale.ROOT);
    }

    @Override
    public Map<String, JsonNode> requiredValues() {
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        requiredValues.forEach((key, value) -> copy.put(key, value.deepCopy()));
        return Collections.unmodifiableMap(copy);
    }

    private static void validatePath(String path) {
        if (path == null || path.length() > 240 || path.chars().anyMatch(Character::isISOControl)
                || (!path.isEmpty() && !path.startsWith("/")) || path.matches(".*~(?![01]).*")) {
            throw new IllegalArgumentException("无效的 JSON 验收路径");
        }
        JsonPointer.compile(path);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (!requiredJson.isEmpty()) snapshot.put(SNAPSHOT_KEY, requiredJson);
        if (!requiredValues.isEmpty()) snapshot.put(VALUE_SNAPSHOT_KEY, requiredValues());
        if (requiredSha256 != null) snapshot.put(SHA_SNAPSHOT_KEY, requiredSha256);
        return snapshot;
    }

    public static AgentAcceptance fromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || !(snapshot.containsKey(SNAPSHOT_KEY) || snapshot.containsKey(VALUE_SNAPSHOT_KEY)
                || snapshot.containsKey(SHA_SNAPSHOT_KEY))) {
            return null;
        }
        Map<String, String> types = new LinkedHashMap<>();
        readRules(snapshot, SNAPSHOT_KEY).forEach((path, type) -> {
            if (!(type instanceof String value)) {
                throw new IllegalStateException("无法读取类型验收快照");
            }
            types.put(path, value);
        });
        Map<String, JsonNode> values = new LinkedHashMap<>();
        readRules(snapshot, VALUE_SNAPSHOT_KEY).forEach((path, value) -> values.put(path, JSON.valueToTree(value)));
        Object rawSha = snapshot.get(SHA_SNAPSHOT_KEY);
        if (snapshot.containsKey(SHA_SNAPSHOT_KEY) && !(rawSha instanceof String)) {
            throw new IllegalStateException("无法读取摘要验收快照");
        }
        return new AgentAcceptance(types, values, (String) rawSha);
    }

    private static Map<String, Object> readRules(Map<String, Object> snapshot, String key) {
        if (!snapshot.containsKey(key)) return Map.of();
        if (!(snapshot.get(key) instanceof Map<?, ?> raw)) {
            throw new IllegalStateException("无法读取执行验收快照");
        }
        Map<String, Object> rules = new LinkedHashMap<>();
        raw.forEach((path, value) -> {
            if (!(path instanceof String pointer)) {
                throw new IllegalStateException("无法读取验收路径");
            }
            rules.put(pointer, value);
        });
        return rules;
    }

    public String prompt() {
        if (requiredJson.isEmpty() && requiredValues.isEmpty()) {
            return "交付终稿必须与预期产物版本的 UTF-8 字节一致。";
        }
        return "交付必须为单个 JSON 值。JSON Pointer 类型断言：" + requiredJson
                + "。JSON Pointer 等值断言：" + requiredValues;
    }

    public Verdict evaluate(String output, boolean degraded) {
        if (output == null || output.isBlank()) {
            return new Verdict(Status.UNVERIFIED, "执行未留下可验收的输出");
        }
        if (!requiredJson.isEmpty() || !requiredValues.isEmpty()) {
            Verdict jsonFailure = validateJson(output);
            if (jsonFailure != null) return jsonFailure;
        }
        if (requiredSha256 != null && !requiredSha256.equals(outputSha256(output))) {
            return new Verdict(Status.REJECTED, "实际交付字节的 SHA-256 与预期产物版本不一致");
        }
        return degraded ? new Verdict(Status.PARTIAL, "已通过验收，执行以降级结果交付")
                : new Verdict(Status.ACCEPTED, null);
    }

    /** 摘要由服务端对终稿计算。修订仅用于能通过候选内容纠正的 JSON 断言。 */
    public boolean canRepairWithModel() {
        return requiredSha256 == null;
    }

    private Verdict validateJson(String output) {
        JsonNode root;
        try {
            root = JSON.readTree(output);
        } catch (JsonProcessingException e) {
            return new Verdict(Status.REJECTED, "交付输出必须为单个合法 JSON 值");
        }
        for (var rule : requiredJson.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            JsonNode value = root.at(rule.getKey());
            if (!matches(value, rule.getValue())) {
                return new Verdict(Status.REJECTED, "JSON 验收未通过，" + label(rule.getKey()) + " 要求类型 " + rule.getValue());
            }
        }
        for (var rule : requiredValues.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            JsonNode actual = root.at(rule.getKey());
            if (!rule.getValue().equals(AgentAcceptance::compareJsonScalar, actual)) {
                return new Verdict(Status.REJECTED, "JSON 验收未通过，" + label(rule.getKey()) + " 与预期值不一致");
            }
        }
        return null;
    }

    private static int compareJsonScalar(JsonNode expected, JsonNode actual) {
        if (expected.isNumber() && actual.isNumber()) {
            return expected.decimalValue().compareTo(actual.decimalValue());
        }
        return expected.equals(actual) ? 0 : 1;
    }

    public static String outputSha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String label(String path) {
        return path.isEmpty() ? "根节点" : path;
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
        ACCEPTED, PARTIAL, UNVERIFIED, REJECTED;

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
