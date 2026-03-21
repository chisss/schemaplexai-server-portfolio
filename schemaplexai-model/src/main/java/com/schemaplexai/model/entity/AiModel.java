package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI模型配置表实体
 */
@Data
@TableName(value = "sf_ai_model", autoResultMap = true)
public class AiModel implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户ID */
    private String tenantId;

    /** 显示名称 */
    private String name;

    /** 提供商: claude/openai/gemini/kimi */
    private String provider;

    /** 提供商编码（关联字典项 item_value，如 Anthropic/OpenAI/DeepSeek） */
    private String providerCode;

    /** 适用场景描述（从字典项同步） */
    private String useCase;

    /** 模型标识 */
    private String modelId;

    /** 加密后的API Key */
    private String apiKeyEncrypted;

    /** API基础URL */
    private String baseUrl;

    /** 默认参数 {temperature, max_tokens, top_p} */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> defaultParams;

    /** 输入Token单价($/1K tokens) */
    private BigDecimal inputPrice;

    /** 输出Token单价($/1K tokens) */
    private BigDecimal outputPrice;

    /** 请求超时时间（秒） */
    private Integer timeoutSeconds;

    /** 重试次数 */
    private Integer retryCount;

    /** 重试间隔（秒） */
    private Integer retryIntervalSeconds;

    /** 最大Token限制 */
    private Integer maxTokens;

    /** 状态: active/inactive */
    private String status;

    /** 最近连通性测试时间 */
    private LocalDateTime lastTestAt;

    /** 最近测试状态: success/failed/timeout */
    private String lastTestStatus;

    /** 最近测试响应延迟（毫秒） */
    private Integer lastTestLatency;

    /** 最近测试错误信息 */
    private String lastTestError;

    /** 最近测试返回的实际模型名称 */
    private String lastTestModel;

    /** 创建人 */
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
