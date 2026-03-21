package com.schemaplexai.service.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Agent 团队上下文共享 MQ 消息体
 *
 * <p>Sub-Agent 执行完成后，WorkflowNodeEngine 通过 {@link com.schemaplexai.service.mq.AgentContextPublisher}
 * 发布此消息到 RabbitMQ {@code sf.agent.team.context} 队列，
 * Consumer 收到后更新 Redis 团队共享上下文，使同一团队的其他 Sub-Agent 可感知产出。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContextMessage implements Serializable {

    /** Team Agent ID（用于定位团队共享上下文 Key） */
    private String teamAgentId;

    /** 产出的 Sub-Agent ID（可为 Solo Agent ID） */
    private String subAgentId;

    /** Agent 执行记录 ID */
    private String executionId;

    /** 所属工作流实例 ID */
    private String instanceId;

    /** 工作流节点 ID */
    private String nodeId;

    /** 节点标签（用于日志可读性） */
    private String nodeLabel;

    /**
     * 消息类型：
     * - {@code agent_output}   — Sub-Agent 产出（主要类型）
     * - {@code context_update} — 上下文条目变更通知
     * - {@code completion}     — 整体团队执行完成通知
     */
    private String messageType;

    /**
     * 产出摘要（最多 2000 字符，防止 MQ 消息体过大）
     */
    private String outputSummary;

    /**
     * Agent 执行最终状态（completed/stopped/failed）
     */
    private String agentStatus;

    /**
     * 质量评分（来自偏离分析节点，可空）
     */
    private Double qualityScore;

    /** 租户 ID */
    private String tenantId;

    /** 消息发布时间 */
    private LocalDateTime publishedAt;
}
