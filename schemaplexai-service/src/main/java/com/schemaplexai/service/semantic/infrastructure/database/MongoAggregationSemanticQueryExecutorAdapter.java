package com.schemaplexai.service.semantic.infrastructure.database;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.schemaplexai.common.enums.McpServerTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.McpServerMapper;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.service.integration.mcp.McpClientService;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionLimits;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryExplainResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryParameter;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import com.schemaplexai.service.semantic.domain.port.SemanticQueryExecutorPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MongoDB 只读 aggregation 适配器。
 *
 * <p>MCP 工具契约为 {@code collection}、{@code pipeline}、{@code limit} 和
 * {@code maxTimeMS}。pipeline 以 JSON 数组传递，参数在服务端解析 JSON 后按节点绑定，
 * 不允许把用户值拼接进 JSON 文本。</p>
 */
@Component
@RequiredArgsConstructor
public final class MongoAggregationSemanticQueryExecutorAdapter implements SemanticQueryExecutorPort {

    private static final List<String> AGGREGATION_TOOLS = List.of(
            "aggregate", "mongodb_aggregate", "aggregate_collection", "run_aggregation", "execute_aggregation");
    private static final List<String> EXPLAIN_TOOLS = List.of(
            "explain_aggregation", "mongodb_explain", "aggregate_explain");
    private static final Set<String> ALLOWED_STAGES = Set.of(
            "$match", "$project", "$group", "$sort", "$limit", "$unwind");
    private static final Set<String> ALLOWED_OPERATORS = Set.of(
            "$eq", "$ne", "$gt", "$gte", "$lt", "$lte", "$in", "$nin", "$sum", "$avg", "$min", "$max",
            "$dateSubtract", "$literal");
    private static final Set<String> FORBIDDEN_OPERATORS = Set.of(
            "$out", "$merge", "$function", "$where", "$accumulator", "$eval", "$currentOp", "$lookup",
            "$unionWith", "$graphLookup", "$search", "$searchMeta", "$densify", "$fill");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\?([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern COLLECTION = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,119}");
    private static final int MAX_PIPELINE_BYTES = 64 * 1024;
    private static final int MAX_STAGES = 16;
    private static final int MAX_DEPTH = 12;

    private final McpServerMapper mcpServerMapper;
    private final McpClientService mcpClientService;
    private final ObjectMapper objectMapper;

    @Override
    public QueryExplainResult explain(QueryPlan plan, QueryExecutionLimits limits) {
        requireMongo(plan);
        BoundAggregation aggregation = bindAndValidate(plan, limits);
        McpServer source = findSource(plan.sourceId());
        String explainTool = findTool(source, EXPLAIN_TOOLS);
        List<String> warnings = new ArrayList<>();
        if (explainTool == null) {
            warnings.add("Mongo aggregation explain 工具不可用，已跳过真实执行");
        } else {
            Map<String, Object> response = mcpClientService.toolsCall(
                    source, explainTool, aggregation.arguments());
            warnings.addAll(readWarnings(response));
        }
        warnings.add("EXPLAIN 仅返回计划元数据，不返回业务数据");
        return new QueryExplainResult(
                plan.planHash(), plan.query().language(), plan.query().statement(),
                plan.query().parameters().stream().map(QueryParameter::name).toList(),
                plan.query().columns(), plan.query().lineage(), warnings);
    }

    @Override
    public QueryExecutionResult execute(QueryPlan plan, QueryExecutionLimits limits) {
        requireMongo(plan);
        BoundAggregation aggregation = bindAndValidate(plan, limits);
        McpServer source = findSource(plan.sourceId());
        String tool = findTool(source, AGGREGATION_TOOLS);
        if (tool == null) {
            throw new BusinessException(ResultCode.SCHEMA_SCAN_FAILED, "数据源未提供受控 Mongo aggregation 工具");
        }
        long startedAt = System.currentTimeMillis();
        Map<String, Object> payload = mcpClientService.toolsCall(source, tool, aggregation.arguments());
        long elapsed = Math.max(0, System.currentTimeMillis() - startedAt);
        List<Map<String, Object>> rows = extractRows(payload);
        boolean truncated = rows.size() > limits.maxRows();
        List<Map<String, Object>> returned = truncated ? rows.subList(0, limits.maxRows()) : rows;
        List<String> warnings = new ArrayList<>(readWarnings(payload));
        if (truncated) {
            warnings.add("结果已按限制行数截断返回");
        }
        return new QueryExecutionResult(
                plan.planHash(), returned, plan.query().columns(), plan.query().lineage(),
                rows.size(), truncated, elapsed, null, warnings);
    }

    private BoundAggregation bindAndValidate(QueryPlan plan, QueryExecutionLimits limits) {
        String statement = plan.query().statement();
        if (statement.length() > MAX_PIPELINE_BYTES) {
            throw new IllegalArgumentException("Mongo aggregation pipeline exceeds size limit");
        }
        String collection = resolveCollection(plan);
        String parseable = PLACEHOLDER.matcher(statement)
                .replaceAll("\\\"__semantic_param_$1__\\\"");
        final JsonNode pipeline;
        try {
            pipeline = objectMapper.readTree(parseable);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Mongo aggregation pipeline is not valid JSON", exception);
        }
        if (pipeline == null || !pipeline.isArray()) {
            throw new IllegalArgumentException("Mongo aggregation pipeline must be a JSON array");
        }
        if (pipeline.size() == 0 || pipeline.size() > MAX_STAGES) {
            throw new IllegalArgumentException("Mongo aggregation stage count is outside the allowed range");
        }
        validateStages(pipeline);
        JsonNode bound = bindParameters(pipeline, plan.query().parameters());
        List<Object> pipelineValue = objectMapper.convertValue(bound, new TypeReference<>() { });
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("collection", collection);
        arguments.put("pipeline", pipelineValue);
        arguments.put("limit", limits.maxRows());
        arguments.put("maxTimeMS", limits.timeoutSeconds() * 1000);
        return new BoundAggregation(arguments);
    }

    private JsonNode bindParameters(JsonNode node, List<QueryParameter> parameters) {
        Map<String, QueryParameter> values = new LinkedHashMap<>();
        parameters.forEach(parameter -> values.put(parameter.name(), parameter));
        return bindNode(node, values, 0);
    }

    private JsonNode bindNode(JsonNode node, Map<String, QueryParameter> parameters, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Mongo aggregation JSON depth exceeds limit");
        }
        if (node.isTextual()) {
            String text = node.textValue();
            if (text.startsWith("__semantic_param_") && text.endsWith("__")) {
                String name = text.substring("__semantic_param_".length(), text.length() - 2);
                QueryParameter parameter = parameters.get(name);
                if (parameter == null) {
                    throw new IllegalArgumentException("Mongo aggregation contains an unbound parameter");
                }
                return scalar(parameter.value());
            }
            return node;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(child -> result.add(bindNode(child, parameters, depth + 1)));
            return result;
        }
        if (node.isObject()) {
            var result = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> result.set(entry.getKey(), bindNode(entry.getValue(), parameters, depth + 1)));
            return result;
        }
        return node;
    }

    private JsonNode scalar(String value) {
        if (value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) {
            return objectMapper.valueToTree(value.contains(".") ? Double.valueOf(value) : Long.valueOf(value));
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return objectMapper.getNodeFactory().booleanNode(Boolean.parseBoolean(value));
        }
        return objectMapper.getNodeFactory().textNode(value);
    }

    private void validateStages(JsonNode pipeline) {
        for (JsonNode stage : pipeline) {
            if (!stage.isObject() || stage.size() != 1) {
                throw new IllegalArgumentException("Mongo aggregation stage must contain exactly one operator");
            }
            String operator = stage.fieldNames().next();
            if (!ALLOWED_STAGES.contains(operator)) {
                throw new IllegalArgumentException("Mongo aggregation stage is not allowed: " + operator);
            }
            if (stage.get(operator).toString().length() > MAX_PIPELINE_BYTES) {
                throw new IllegalArgumentException("Mongo aggregation stage exceeds size limit");
            }
            validateOperators(stage.get(operator), 0);
        }
    }

    private void validateOperators(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Mongo aggregation JSON depth exceeds limit");
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                if (FORBIDDEN_OPERATORS.contains(key)
                        || (key.startsWith("$") && !ALLOWED_STAGES.contains(key) && !ALLOWED_OPERATORS.contains(key))) {
                    throw new IllegalArgumentException("Mongo aggregation operator is not allowed: " + key);
                }
                validateOperators(entry.getValue(), depth + 1);
            });
        } else if (node.isArray()) {
            node.forEach(child -> validateOperators(child, depth + 1));
        }
    }

    private String resolveCollection(QueryPlan plan) {
        Set<String> objects = new LinkedHashSet<>();
        plan.query().lineage().forEach(lineage -> objects.add(lineage.physicalObject()));
        if (objects.size() != 1) {
            throw new IllegalArgumentException("Mongo aggregation requires one collection");
        }
        String collection = objects.iterator().next();
        if (!COLLECTION.matcher(collection).matches()) {
            throw new IllegalArgumentException("invalid Mongo collection identifier");
        }
        return collection;
    }

    private McpServer findSource(String sourceId) {
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (!StringUtils.hasText(tenantId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "租户上下文缺失");
        }
        McpServer source = mcpServerMapper.selectOne(new LambdaQueryWrapper<McpServer>()
                .eq(McpServer::getTenantId, tenantId)
                .eq(McpServer::getId, sourceId)
                .eq(McpServer::getServerType, McpServerTypeEnum.DATABASE.getCode())
                .last("LIMIT 1"));
        if (source == null) {
            throw new BusinessException(ResultCode.MCP_SERVER_NOT_FOUND);
        }
        return source;
    }

    private String findTool(McpServer source, List<String> candidates) {
        List<Map<String, Object>> tools = new ArrayList<>();
        if (source.getTools() != null) {
            source.getTools().forEach(tool -> {
                if (tool instanceof Map<?, ?> map) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    map.forEach((key, value) -> normalized.put(String.valueOf(key), value));
                    tools.add(normalized);
                }
            });
        }
        if (tools.isEmpty()) {
            tools.addAll(mcpClientService.discoverTools(source));
        }
        for (String candidate : candidates) {
            for (Map<String, Object> tool : tools) {
                if (candidate.equalsIgnoreCase(String.valueOf(tool.get("name")))) {
                    return String.valueOf(tool.get("name"));
                }
            }
        }
        return null;
    }

    private List<Map<String, Object>> extractRows(Map<String, Object> payload) {
        return extractRows(objectMapper.valueToTree(payload), 0);
    }

    private List<Map<String, Object>> extractRows(JsonNode node, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return List.of();
        }
        if (node.isTextual()) {
            try {
                return extractRows(objectMapper.readTree(node.textValue()), depth + 1);
            } catch (Exception ignored) {
                return List.of();
            }
        }
        if (node.isArray()) {
            List<Map<String, Object>> rows = toRows(node);
            if (!rows.isEmpty()) {
                return rows;
            }
            for (JsonNode child : node) {
                List<Map<String, Object>> nested = extractRows(child, depth + 1);
                if (!nested.isEmpty()) {
                    return nested;
                }
            }
            return List.of();
        }
        if (!node.isObject()) {
            return List.of();
        }
        for (String key : List.of("rows", "data", "results", "documents", "result", "content")) {
            JsonNode candidate = node.get(key);
            if (candidate != null) {
                List<Map<String, Object>> nested = extractRows(candidate, depth + 1);
                if (!nested.isEmpty()) {
                    return nested;
                }
            }
        }
        return List.of();
    }

    private List<Map<String, Object>> toRows(JsonNode array) {
        List<Map<String, Object>> rows = new ArrayList<>();
        array.forEach(item -> {
            if (item.isObject()) {
                JsonNode type = item.get("type");
                JsonNode text = item.get("text");
                if (type != null && text != null && "text".equalsIgnoreCase(type.asText())) {
                    try {
                        rows.addAll(extractRows(objectMapper.readTree(text.asText()), 1));
                    } catch (Exception ignored) {
                        // MCP 文本载荷无法解析时不把协议包装字段当作业务行返回。
                    }
                } else {
                    rows.add(objectMapper.convertValue(item, new TypeReference<>() { }));
                }
            }
        });
        return rows;
    }

    private List<String> readWarnings(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get("warnings");
        if (value instanceof List<?> list) {
            return list.stream().filter(item -> item != null).map(String::valueOf).toList();
        }
        return value == null ? List.of() : List.of(String.valueOf(value));
    }

    private void requireMongo(QueryPlan plan) {
        if (plan == null || !"MONGO_AGGREGATION".equals(plan.query().language())) {
            throw new IllegalArgumentException("Mongo adapter only supports compiled aggregation plans");
        }
    }

    private record BoundAggregation(Map<String, Object> arguments) {
    }
}
