package com.schemaplexai.service.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.model.entity.ApiGateway;
import com.schemaplexai.model.vo.gateway.ApiGatewayTestResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiGatewayExecutor {

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public ApiGatewayTestResult execute(ApiGateway gateway,
                                        Map<String, Object> overrideHeaders,
                                        Map<String, Object> overrideQueryParams,
                                        String overrideBody) {
        var result = new ApiGatewayTestResult();
        long startTime = System.currentTimeMillis();

        try {
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
                result.setResponseBody(response.body().string());
            }
            response.close();

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
}
