package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.schemaplexai.model.vo.monitor.ModelAttemptMetadata;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Agent 调用链 Span
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_agent_trace_span", autoResultMap = true)
public class AgentTraceSpan extends BaseEntity {

    /**
     * 调用链 ID
     */
    private String traceId;

    /**
     * Span ID
     */
    private String spanId;

    /**
     * 父 Span ID
     */
    private String parentSpanId;

    /**
     * Agent ID
     */
    private String agentId;

    /**
     * Span 类型：AGENT/LLM/TOOL/WORKFLOW
     */
    private String spanType;

    /**
     * Span 名称
     */
    private String name;

    /**
     * 开始时间
     */
    private LocalDateTime startedAt;

    /**
     * 持续时长（毫秒）
     */
    private Long durationMs;

    /**
     * 输入 Token
     */
    private Long inputTokens;

    /**
     * 输出 Token
     */
    private Long outputTokens;

    /**
     * 调用成本
     */
    private BigDecimal cost;

    /**
     * 状态
     */
    private String status;

    /**
     * 失败分类
     */
    private String failureCategory;

    /**
     * 失败原因
     */
    private String failureReason;

    /**
     * 是否可恢复
     */
    private Boolean recoverable;

    /** 预算内模型单次请求的计量明细。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private ModelAttemptMetadata modelAttempt;
}
