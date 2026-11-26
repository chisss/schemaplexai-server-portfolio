package com.schemaplexai.service.rag.ingest.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * 外部 OCR API（降级备选，默认关闭）。
 *
 * <p>只在 {@code rag.ocr.api.url} 配置后才可用，且必须由租户把 {@code ocrMode} 显式切到 {@code api}
 * 才会被调用 —— 不做"内部模型挂了就偷偷出境"的自动降级。
 *
 * <p>协议按"通用 OCR 网关"约定：POST JSON {@code {"image":"<base64>","mime":"image/png"}}，
 * 响应 {@code {"text":"...","confidence":0.97}}。这样对接阿里云/百度/自建网关时只需一层薄适配，
 * 不必把某家 SDK 焊进核心链路。
 */
@Slf4j
@Component
public class ExternalOcrApiClient implements OcrClient {

    static final String MODE = "api";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String endpoint;
    private final String apiKey;
    private final int timeoutSeconds;

    public ExternalOcrApiClient(ObjectMapper objectMapper,
                                @Value("${rag.ocr.api.url:}") String endpoint,
                                @Value("${rag.ocr.api.key:}") String apiKey,
                                @Value("${rag.ocr.api.timeout-seconds:30}") int timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.timeoutSeconds = timeoutSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String mode() {
        return MODE;
    }

    public boolean isConfigured() {
        return StringUtils.hasText(endpoint);
    }

    @Override
    public OcrResult recognize(OcrRequest request) {
        if (!isConfigured()) {
            throw new OcrException("OCR_API_NOT_CONFIGURED",
                    "外部 OCR API 未配置（rag.ocr.api.url 为空）");
        }
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "image", Base64.getEncoder().encodeToString(request.imageBytes()),
                    "mime", StringUtils.hasText(request.mimeType()) ? request.mimeType() : "image/png",
                    "fileName", request.fileName() == null ? "" : request.fileName(),
                    "page", request.pageIndex()));

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (StringUtils.hasText(apiKey)) {
                builder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OcrException("OCR_HTTP_ERROR",
                        "外部 OCR API 调用失败: status=" + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String text = root.path("text").asText("");
            double confidence = root.path("confidence").asDouble(0.8D);
            return new OcrResult(text, confidence, OcrResult.SOURCE_EXTERNAL_API);
        } catch (OcrException e) {
            throw e;
        } catch (Exception e) {
            log.warn("外部 OCR API 调用异常: fileName={}, error={}", request.fileName(), e.getMessage());
            throw new OcrException("OCR_CALL_FAILED", "外部 OCR API 调用异常: " + e.getMessage(), e);
        }
    }
}
