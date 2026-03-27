package com.schemaplexai.model.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 创建AI模型请求DTO
 */
@Data
public class AiModelCreateRequest {

    /** 显示名称 */
    @NotBlank(message = "模型名称不能为空")
    private String name;

    /** 提供商: claude/openai/gemini/kimi */
    @NotBlank(message = "提供商不能为空")
    private String provider;

    /** 提供商编码 */
    private String providerCode;

    /** 模型用途分类 */
    private String useCase;

    /** 模型标识 */
    @NotBlank(message = "模型标识不能为空")
    private String modelId;

    /** API Key */
    @NotBlank(message = "API Key不能为空")
    private String apiKey;

    /** API基础URL */
    private String baseUrl;

    /** 默认参数 */
    private Map<String, Object> defaultParams;

    /** 输入Token单价 */
    private BigDecimal inputPrice;

    /** 输出Token单价 */
    private BigDecimal outputPrice;

    /** 超时时间（秒） */
    private Integer timeoutSeconds;

    /** 重试次数 */
    private Integer retryCount;

    /** 重试间隔（秒） */
    private Integer retryIntervalSeconds;

    /** 最大Token限制 */
    private Integer maxTokens;
}
