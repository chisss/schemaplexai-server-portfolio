package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * 路由决策日志
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_routing_decision_log", autoResultMap = true)
public class RoutingDecisionLog extends BaseEntity {

    /**
     * Agent ID
     */
    private String agentId;

    /**
     * 请求 ID
     */
    private String requestId;

    /**
     * 最终选中的模型 ID
     */
    private String selectedModelId;

    /**
     * 使用的策略名称
     */
    private String strategy;

    /**
     * 候选模型链路
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> candidateModels;

    /**
     * 路由原因
     */
    private String reason;

    /**
     * 延迟（毫秒）
     */
    private Long latencyMs;

    /**
     * Token 总数
     */
    private Long tokenCount;

    /**
     * 调用成本
     */
    private BigDecimal cost;
}
