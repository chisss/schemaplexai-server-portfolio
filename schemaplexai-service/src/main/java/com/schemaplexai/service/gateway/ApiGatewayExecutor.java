package com.schemaplexai.service.gateway;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schemaplexai.dao.mapper.ApiGatewayLogMapper;
import com.schemaplexai.dao.mapper.ApiGatewayPolicyMapper;
import com.schemaplexai.model.entity.ApiGateway;
import com.schemaplexai.model.entity.ApiGatewayLog;
import com.schemaplexai.model.entity.ApiGatewayPolicy;
import com.schemaplexai.model.vo.gateway.ApiGatewayTestResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiGatewayExecutor {

    private static final int STATUS_TOO_MANY_REQUESTS = 429;

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final ApiGatewayPolicyMapper policyMapper;
    private final ApiGatewayLogMapper logMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public ApiGatewayTestResult execute(ApiGateway gateway,
                                        Map<String, Object> overrideHeaders,
                                        Map<String, Object> overrideQueryParams,
                                        String overrideBody) {
        var result = new ApiGatewayTestResult();
        long startTime = System.currentTimeMillis();
        ApiGatewayPolicy policy = loadPolicy(gateway.getId());
        result.setCost(resolveCost(policy));

        try {
            enforcePolicy(gateway, policy);
            String url = buildUrl(gateway, overrideQueryParams);
            var requestBuilder = new Request.Builder().url(url);

            applyAuth(requestBuilder, gateway);
            applyHeaders(requestBuilder, gateway, overrideHeaders);

            String method = gateway.getMethod().toUpperCase();
            String body = overrideBody;
            if (body == null && gateway.getRequestBodySchema() != null
                    && gateway.getRequestBodySchema().containsKey("example")) {
                body = objectMapper.writeValueAsString(gateway.getRequestBodySchema().get("example"));
            }

            switch (method) {
                case "GET" -> requestBuilder.get();
                case "DELETE" -> requestBuilder.delete();
                case "POST" -> requestBuilder.post(buildRequestBody(body, gateway.getContentType()));
                case "PUT" -> requestBuilder.put(buildRequestBody(body, gateway.getContentType()));
                case "PATCH" -> requestBuilder.patch(buildRequestBody(body, gateway.getContentType()));
                default -> requestBuilder.get();
            }

            var client = okHttpClient.newBuilder()
                    .connectTimeout(gateway.getTimeoutMs(), TimeUnit.MILLISECONDS)
                    .readTimeout(gateway.getTimeoutMs(), TimeUnit.MILLISECONDS)
                    .build();

            int retries = gateway.getRetryCount() != null ? gateway.getRetryCount() : 0;
            Response response = null;
            IOException lastException = null;

            for (int i = 0; i <= retries; i++) {
                try {
                    response = client.newCall(requestBuilder.build()).execute();
                    break;
                } catch (IOException e) {
                    lastException = e;
                    if (i < retries) {
                        log.warn("API调用重试 {}/{}: {}", i + 1, retries, e.getMessage());
                    }
                }
            }

            if (response == null) {
                throw lastException;
            }

            result.setSuccess(response.isSuccessful());
            result.setStatusCode(response.code());
            result.setDurationMs(System.currentTimeMillis() - startTime);

            var responseHeaders = new HashMap<String, Object>();
            response.headers().forEach(pair -> responseHeaders.put(pair.getFirst(), pair.getSecond()));
            result.setResponseHeaders(responseHeaders);

            if (response.body() != null) {
                result.setResponseBody(applyMaskingRules(response.body().string(), policy));
            }
            response.close();

        } catch (PolicyViolationException e) {
            result.setSuccess(false);
            result.setStatusCode(e.statusCode());
            result.setDurationMs(System.currentTimeMillis() - startTime);
            result.setErrorMessage(e.getMessage());
            log.warn("API网关策略拦截: gatewayId={}, error={}", gateway.getId(), e.getMessage());
        } catch (Exception e) {
            result.setSuccess(false);
            result.setDurationMs(System.currentTimeMillis() - startTime);
            result.setErrorMessage(e.getMessage());
            log.error("API网关调用失败: gatewayId={}, error={}", gateway.getId(), e.getMessage());
        }

        return result;
    }

    private String buildUrl(ApiGateway gateway, Map<String, Object> overrideQueryParams) {
        var sb = new StringBuilder(gateway.getUrl());
        var params = new HashMap<String, Object>();
        if (gateway.getQueryParams() != null) {
            params.putAll(gateway.getQueryParams());
        }
        if (overrideQueryParams != null) {
            params.putAll(overrideQueryParams);
        }
        if (!params.isEmpty()) {
            sb.append(gateway.getUrl().contains("?") ? "&" : "?");
            var entries = params.entrySet().iterator();
            while (entries.hasNext()) {
                var entry = entries.next();
                sb.append(entry.getKey()).append("=").append(entry.getValue());
                if (entries.hasNext()) {
                    sb.append("&");
                }
            }
        }
        return sb.toString();
    }

    private void enforcePolicy(ApiGateway gateway, ApiGatewayPolicy policy) {
        if (policy == null || Boolean.FALSE.equals(policy.getEnabled())) {
            return;
        }
        enforceRateLimit(gateway, policy);
        enforceCostLimit(gateway, policy);
    }

    private void enforceRateLimit(ApiGateway gateway, ApiGatewayPolicy policy) {
        LocalDateTime now = LocalDateTime.now();
        String prefix = "api_gateway:limit:" + gateway.getId();
        checkRateLimit(prefix + ":minute:" + now.getYear() + now.getDayOfYear() + now.getHour() + now.getMinute(),
                policy.getRateLimitPerMinute(), Duration.ofMinutes(2), "每分钟");
        checkRateLimit(prefix + ":hour:" + now.getYear() + now.getDayOfYear() + now.getHour(),
                policy.getRateLimitPerHour(), Duration.ofHours(2), "每小时");
        checkRateLimit(prefix + ":day:" + now.toLocalDate(),
                policy.getRateLimitPerDay(), Duration.ofDays(2), "每日");
    }

    private void checkRateLimit(String key, Integer limit, Duration ttl, String label) {
        if (limit == null || limit <= 0) {
            return;
        }
        Long current = stringRedisTemplate.opsForValue().increment(key);
        if (current != null && current == 1L) {
            stringRedisTemplate.expire(key, ttl);
        }
        if (current != null && current > limit) {
            throw new PolicyViolationException(STATUS_TOO_MANY_REQUESTS, "API Gateway 已触发" + label + "限流");
        }
    }

    private void enforceCostLimit(ApiGateway gateway, ApiGatewayPolicy policy) {
        BigDecimal currentCallCost = resolveCost(policy);
        LocalDateTime now = LocalDateTime.now();
        if (policy.getDailyCostLimit() != null && policy.getDailyCostLimit().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal dailyCost = sumLoggedCost(gateway, now.toLocalDate().atStartOfDay(), now.plusSeconds(1));
            if (dailyCost.add(currentCallCost).compareTo(policy.getDailyCostLimit()) > 0) {
                throw new PolicyViolationException(STATUS_TOO_MANY_REQUESTS, "API Gateway 已超过当日成本上限");
            }
        }
        if (policy.getMonthlyCostLimit() != null && policy.getMonthlyCostLimit().compareTo(BigDecimal.ZERO) > 0) {
            LocalDateTime monthStart = LocalDateTime.of(now.getYear(), now.getMonth(), 1, 0, 0);
            BigDecimal monthlyCost = sumLoggedCost(gateway, monthStart, now.plusSeconds(1));
            if (monthlyCost.add(currentCallCost).compareTo(policy.getMonthlyCostLimit()) > 0) {
                throw new PolicyViolationException(STATUS_TOO_MANY_REQUESTS, "API Gateway 已超过当月成本上限");
            }
        }
    }

    private BigDecimal sumLoggedCost(ApiGateway gateway, LocalDateTime start, LocalDateTime end) {
        List<ApiGatewayLog> logs = logMapper.selectList(new LambdaQueryWrapper<ApiGatewayLog>()
                .eq(ApiGatewayLog::getGatewayId, gateway.getId())
                .eq(StringUtils.hasText(gateway.getTenantId()), ApiGatewayLog::getTenantId, gateway.getTenantId())
                .ge(ApiGatewayLog::getCreatedAt, start)
                .lt(ApiGatewayLog::getCreatedAt, end));
        return logs.stream()
                .map(item -> item.getCost() == null ? BigDecimal.ZERO : item.getCost())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private ApiGatewayPolicy loadPolicy(String gatewayId) {
        if (!StringUtils.hasText(gatewayId)) {
            return null;
        }
        return policyMapper.selectOne(new LambdaQueryWrapper<ApiGatewayPolicy>()
                .eq(ApiGatewayPolicy::getGatewayId, gatewayId)
                .last("limit 1"));
    }

    private BigDecimal resolveCost(ApiGatewayPolicy policy) {
        if (policy == null || policy.getCostPerCall() == null) {
            return BigDecimal.ZERO;
        }
        return policy.getCostPerCall().setScale(6, RoundingMode.HALF_UP);
    }

    private String applyMaskingRules(String responseBody, ApiGatewayPolicy policy) {
        if (!StringUtils.hasText(responseBody) || policy == null || CollectionUtils.isEmpty(policy.getDataMaskingRules())) {
            return responseBody;
        }
        String maskedBody = responseBody;
        for (Object rule : policy.getDataMaskingRules()) {
            maskedBody = applyMaskingRule(maskedBody, rule);
        }
        return maskedBody;
    }

    private String applyMaskingRule(String content, Object rule) {
        if (!StringUtils.hasText(content) || rule == null) {
            return content;
        }
        if (rule instanceof String pattern && StringUtils.hasText(pattern)) {
            return content.replaceAll(pattern, "***");
        }
        if (!(rule instanceof Map<?, ?> rawRule)) {
            return content;
        }
        String field = stringify(rawRule.get("field"));
        String pattern = StringUtils.hasText(stringify(rawRule.get("pattern")))
                ? stringify(rawRule.get("pattern"))
                : stringify(rawRule.get("regex"));
        String replacement = StringUtils.hasText(stringify(rawRule.get("replacement")))
                ? stringify(rawRule.get("replacement"))
                : "***";
        if (StringUtils.hasText(field)) {
            String fieldMaskedContent = tryMaskJsonField(content, field, replacement);
            if (fieldMaskedContent != null) {
                return fieldMaskedContent;
            }
        }
        if (StringUtils.hasText(pattern)) {
            return content.replaceAll(pattern, replacement);
        }
        return content;
    }

    private String tryMaskJsonField(String content, String field, String replacement) {
        try {
            JsonNode rootNode = objectMapper.readTree(content);
            if (maskJsonField(rootNode, field, replacement)) {
                return objectMapper.writeValueAsString(rootNode);
            }
        } catch (Exception e) {
            log.debug("API网关响应字段脱敏失败，回退为正则替换: field={}, error={}", field, e.getMessage());
        }
        return null;
    }

    private boolean maskJsonField(JsonNode node, String field, String replacement) {
        if (node instanceof ObjectNode objectNode) {
            boolean changed = false;
            Iterator<Map.Entry<String, JsonNode>> iterator = objectNode.fields();
            while (iterator.hasNext()) {
                Map.Entry<String, JsonNode> entry = iterator.next();
                if (field.equals(entry.getKey())) {
                    objectNode.put(entry.getKey(), replacement);
                    changed = true;
                    continue;
                }
                changed = maskJsonField(entry.getValue(), field, replacement) || changed;
            }
            return changed;
        }
        if (node instanceof ArrayNode arrayNode) {
            boolean changed = false;
            for (JsonNode childNode : arrayNode) {
                changed = maskJsonField(childNode, field, replacement) || changed;
            }
            return changed;
        }
        return false;
    }

    private String stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void applyAuth(Request.Builder builder, ApiGateway gateway) {
        if (gateway.getAuthConfig() == null || "none".equals(gateway.getAuthType())) {
            return;
        }
        var config = gateway.getAuthConfig();
        switch (gateway.getAuthType()) {
            case "bearer" -> {
                String token = (String) config.get("token");
                if (token != null) {
                    builder.header("Authorization", "Bearer " + token);
                }
            }
            case "basic" -> {
                String username = (String) config.get("username");
                String password = (String) config.get("password");
                if (username != null && password != null) {
                    String credentials = Base64.getEncoder()
                            .encodeToString((username + ":" + password).getBytes());
                    builder.header("Authorization", "Basic " + credentials);
                }
            }
            case "api_key" -> {
                String key = (String) config.get("key");
                String value = (String) config.get("value");
                String position = (String) config.getOrDefault("position", "header");
                if ("header".equals(position) && key != null && value != null) {
                    builder.header(key, value);
                }
            }
            default -> { }
        }
    }

    private void applyHeaders(Request.Builder builder, ApiGateway gateway,
                              Map<String, Object> overrideHeaders) {
        if (gateway.getHeaders() != null) {
            gateway.getHeaders().forEach((k, v) -> builder.header(k, String.valueOf(v)));
        }
        if (overrideHeaders != null) {
            overrideHeaders.forEach((k, v) -> builder.header(k, String.valueOf(v)));
        }
    }

    private RequestBody buildRequestBody(String body, String contentType) {
        String ct = contentType != null ? contentType : "application/json";
        String content = body != null ? body : "";
        return RequestBody.create(content, MediaType.parse(ct));
    }

    private static class PolicyViolationException extends RuntimeException {

        private final int statusCode;

        private PolicyViolationException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        private int statusCode() {
            return statusCode;
        }
    }
}
