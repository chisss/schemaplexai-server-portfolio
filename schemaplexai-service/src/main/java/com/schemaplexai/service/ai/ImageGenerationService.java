package com.schemaplexai.service.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.model.entity.AiModel;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 生图模型调用服务，兼容 OpenAI images/generations 协议。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageGenerationService {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final String DEFAULT_SIZE = "1536x1024";
    private static final String DEFAULT_QUALITY = "high";
    private static final int DEFAULT_N = 1;

    private final AiModelMapper aiModelMapper;
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public ImageGenerationResult generate(ImageGenerationRequest request) {
        if (request == null || !StringUtils.hasText(request.getModelId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "生图模型不能为空");
        }
        if (!StringUtils.hasText(request.getPrompt())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "生图描述不能为空");
        }
        AiModel model = aiModelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getId, request.getModelId())
                .eq(AiModel::getStatus, CommonConstant.STATUS_ACTIVE)
                .last("limit 1"));
        if (model == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "指定生图模型不存在或未启用");
        }
        if (!isImageModel(model)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请选择生图用途模型");
        }
        AiModelConfig config = AiModelConfig.from(model);
        String endpoint = resolveImageEndpoint(model.getBaseUrl(), model);
        Map<String, Object> payload = buildPayload(config, model, request);
        Request httpRequest = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(objectMapper.valueToTree(payload).toString(), JSON_MEDIA_TYPE))
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .build();
        OkHttpClient client = okHttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(Math.max(30, config.getTimeoutSeconds())))
                .writeTimeout(Duration.ofSeconds(20))
                .build();
        try (Response response = client.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.warn("生图模型调用失败: modelId={}, status={}, body={}", model.getId(), response.code(), responseBody);
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "生图模型调用失败: HTTP " + response.code() + "，" + truncateErrorBody(responseBody));
            }
            return parseResult(model, request, responseBody);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("生图模型调用异常: modelId={}, error={}", model.getId(), exception.getMessage(), exception);
            throw new BusinessException(ResultCode.BAD_REQUEST, "生图模型调用异常: " + exception.getMessage());
        }
    }

    private Map<String, Object> buildPayload(AiModelConfig config, AiModel model, ImageGenerationRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.getModelId());
        payload.put("prompt", request.getPrompt().trim());
        payload.put("size", StringUtils.hasText(request.getSize()) ? request.getSize().trim() : resolveDefaultParam(model, "size", DEFAULT_SIZE));
        if (hasDefaultParam(model, "quality") || StringUtils.hasText(request.getQuality())) {
            payload.put("quality", StringUtils.hasText(request.getQuality()) ? request.getQuality().trim() : resolveDefaultParam(model, "quality", DEFAULT_QUALITY));
        }
        if (hasDefaultParam(model, "n") || request.getN() != null) {
            payload.put("n", request.getN() != null && request.getN() > 0 ? Math.min(request.getN(), 4) : DEFAULT_N);
        }
        copyDefaultParam(payload, model, "response_format");
        copyDefaultParam(payload, model, "sequential_image_generation");
        copyDefaultParam(payload, model, "stream");
        copyDefaultParam(payload, model, "watermark");
        return payload;
    }

    private ImageGenerationResult parseResult(AiModel model, ImageGenerationRequest request, String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode data = root.path("data");
        List<String> imageUrls = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                String url = item.path("url").asText(null);
                if (StringUtils.hasText(url)) {
                    imageUrls.add(url);
                    continue;
                }
                String b64Json = item.path("b64_json").asText(null);
                if (StringUtils.hasText(b64Json)) {
                    imageUrls.add("data:image/png;base64," + b64Json);
                }
            }
        }
        if (imageUrls.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "生图模型未返回图片");
        }
        return ImageGenerationResult.builder()
                .modelId(model.getId())
                .modelName(model.getName())
                .prompt(request.getPrompt())
                .size(StringUtils.hasText(request.getSize()) ? request.getSize() : DEFAULT_SIZE)
                .quality(StringUtils.hasText(request.getQuality()) ? request.getQuality() : DEFAULT_QUALITY)
                .imageUrls(imageUrls)
                .build();
    }

    public boolean isImageModel(AiModel model) {
        if (model == null) {
            return false;
        }
        String useCase = model.getUseCase() != null ? model.getUseCase().toLowerCase(Locale.ROOT) : "";
        String modelId = model.getModelId() != null ? model.getModelId().toLowerCase(Locale.ROOT) : "";
        return useCase.contains("image") || useCase.contains("生图") || modelId.contains("image");
    }

    private String resolveDefaultParam(AiModel model, String key, String defaultValue) {
        if (model.getDefaultParams() == null || !model.getDefaultParams().containsKey(key)) {
            return defaultValue;
        }
        Object value = model.getDefaultParams().get(key);
        return value != null && StringUtils.hasText(String.valueOf(value)) ? String.valueOf(value) : defaultValue;
    }

    private boolean hasDefaultParam(AiModel model, String key) {
        return model != null && model.getDefaultParams() != null && model.getDefaultParams().containsKey(key);
    }

    private void copyDefaultParam(Map<String, Object> payload, AiModel model, String key) {
        if (!hasDefaultParam(model, key)) {
            return;
        }
        Object value = model.getDefaultParams().get(key);
        if (value != null) {
            payload.put(key, value);
        }
    }

    private String resolveImageEndpoint(String baseUrl, AiModel model) {
        if (!StringUtils.hasText(baseUrl)) {
            return "https://api.openai.com/v1/images/generations";
        }
        String trimmed = baseUrl.trim();
        if ("exact".equalsIgnoreCase(resolveDefaultParam(model, "endpointMode", ""))) {
            return trimmed;
        }
        if (trimmed.endsWith("/images/generations")) {
            return trimmed;
        }
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/chat/completions")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/chat/completions".length());
        }
        if (trimmed.endsWith("/v1")) {
            return trimmed + "/images/generations";
        }
        return trimmed + "/v1/images/generations";
    }

    private String truncateErrorBody(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return "模型服务未返回错误详情";
        }
        String text = responseBody.trim();
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    @Data
    public static class ImageGenerationRequest {
        private String modelId;
        private String prompt;
        private String size;
        private String quality;
        private Integer n;
    }

    @Data
    @Builder
    public static class ImageGenerationResult {
        private String modelId;
        private String modelName;
        private String prompt;
        private String size;
        private String quality;
        private List<String> imageUrls;
    }
}
