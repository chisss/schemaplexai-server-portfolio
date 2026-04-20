package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Agent长期记忆
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_agent_memory", autoResultMap = true)
public class AgentMemory extends BaseEntity {

    /** Agent ID */
    private String agentId;

    /** 执行ID（来源） */
    private String executionId;

    /** 记忆类型: FACT/PREFERENCE/CONSTRAINT/PATTERN */
    private String memoryType;

    /** 阶段: RAW/CONSOLIDATED */
    private String phase;

    /** 记忆内容 */
    private String content;

    /** 相关性评分 */
    private BigDecimal relevanceScore;

    /** 来源对话轮次数 */
    private Integer sourceTurnCount;

    /** 过期时间 */
    private LocalDateTime expiresAt;

    /** 合并目标ID */
    private String consolidatedInto;
}
