package com.schemaplexai.service.rag.ingest.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.service.ai.AiModelConfig;
import lombok.extern.slf4j.Slf4j;
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
 * 平台内部多模态模型 OCR（首选路径）。
 *
 * <p>走 OpenAI 兼容的 {@code /chat/completions} 协议，把页面图片以 base64 data url 传入，
 * 让模型用内置 OCR 能力返回纯文本。这样不引入任何第三方 OCR 服务，文档不出内网。
 *
 * <p>为什么不新建独立 OCR 推理服务：修订版已明确下线跨语言微服务；
 * 平台已有模型资源，复用即可，只需在并发上做隔离（见 {@code MultimodalOcrParseHandler}）。
 */
@Slf4j
@Component
public class PlatformMultimodalOcrClient implements OcrClient {

    static final String MODE = "multimodal";

    private static final String OCR_PROMPT = """
            你是文档 OCR 引擎。请逐字提取图片中的全部文字，并遵守：
            1. 保留表格结构，用 Markdown 表格输出；
            2. 保留标题层级（#、##、###）；
            3. 不要总结、不要翻译、不要解释、不要添加任何前后缀说明；
            4. 若图片中没有文字，只输出空内容。
            """;

    private final AiModelMapper aiModelMapper;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PlatformMultimodalOcrClient(AiModelMapper aiModelMapper, ObjectMapper objectMapper) {
        this.aiModelMapper = aiModelMapper;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String mode() {
        return MODE;
    }

    @Override
    public OcrResult recognize(OcrRequest request) {
        AiModelConfig config = resolveConfig(request.ocrModelConfigId());
        String baseUrl = trimTrailingSlash(config.getBaseUrl());
        if (!StringUtils.hasText(baseUrl)) {
            throw new OcrException("OCR_CONFIG_MISSING", "多模态模型未配置 baseUrl");
        }

        try {
            String body = buildRequestBody(config, request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(Math.max(15, config.getTimeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OcrException("OCR_HTTP_ERROR",
                        "多模态 OCR 调用失败: status=" + response.statusCode() + ", body=" + abbreviate(response.body()));
            }
            String text = extractContent(response.body());
            return new OcrResult(text, 1.0D, OcrResult.SOURCE_MULTIMODAL);
        } catch (OcrException e) {
            throw e;
        } catch (Exception e) {
            log.warn("多模态 OCR 调用异常: fileName={}, page={}, error={}",
                    request.fileName(), request.pageIndex(), e.getMessage());
            throw new OcrException("OCR_CALL_FAILED", "多模态 OCR 调用异常: " + e.getMessage(), e);
        }
    }

    private String buildRequestBody(AiModelConfig config, OcrRequest request) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", config.getModelId());
        root.put("temperature", 0);
        root.put("max_tokens", 4096);

        ArrayNode messages = root.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        ArrayNode content = userMessage.putArray("content");

        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", OCR_PROMPT);

        ObjectNode imagePart = content.addObject();
        imagePart.put("type", "image_url");
        ObjectNode imageUrl = imagePart.putObject("image_url");
        String mime = StringUtils.hasText(request.mimeType()) ? request.mimeType() : "image/png";
        imageUrl.put("url", "data:" + mime + ";base64,"
                + Base64.getEncoder().encodeToString(request.imageBytes()));

        return objectMapper.writeValueAsString(root);
    }

    private String extractContent(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new OcrException("OCR_EMPTY_RESPONSE", "多模态 OCR 返回结果为空");
        }
        return choices.get(0).path("message").path("content").asText("");
    }

    private AiModelConfig resolveConfig(String modelConfigId) {
        if (!StringUtils.hasText(modelConfigId)) {
            throw new OcrException("OCR_CONFIG_MISSING", "未绑定 OCR 多模态模型");
        }
        AiModel model = aiModelMapper.selectById(modelConfigId);
        if (model == null) {
            throw new OcrException("OCR_CONFIG_MISSING", "OCR 多模态模型不存在: " + modelConfigId);
        }
        return AiModelConfig.from(model);
    }

    private String trimTrailingSlash(String url) {
        if (!StringUtils.hasText(url)) {
            return url;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }
}
