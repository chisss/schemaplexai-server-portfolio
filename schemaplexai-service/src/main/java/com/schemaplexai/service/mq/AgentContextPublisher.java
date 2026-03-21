package com.schemaplexai.service.mq;

import com.schemaplexai.service.mq.message.AgentContextMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Agent 上下文共享 MQ 发布者
 *
 * <p>发布目标：
 * <pre>
 * Exchange:    sf.agent
 * RoutingKey:  agent.team.context.{teamAgentId}
 * Queue:       sf.agent.team.context（在 RabbitMQConfig 中声明）
 * </pre>
 *
 * <p>调用方：{@link com.schemaplexai.service.workflow.engine.WorkflowNodeEngine#onAgentNodeCompleted}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentContextPublisher {

    private static final String EXCHANGE = "sf.agent";
    private static final String ROUTING_KEY_PREFIX = "agent.team.context.";

    private final RabbitTemplate rabbitTemplate;

    /**
     * 发布 Agent 产出消息（Agent 节点执行完成时调用）
     *
     * @param teamAgentId  Team Agent ID（Solo Agent 场景也可传 agentId，Consumer 会处理）
     * @param subAgentId   执行的 Agent ID
     * @param executionId  Agent 执行记录 ID
     * @param instanceId   工作流实例 ID
     * @param nodeId       节点 ID
     * @param nodeLabel    节点标签
     * @param agentStatus  执行状态
     * @param outputText   原始产出文本（自动截断到2000字符）
     * @param tenantId     租户 ID
     */
    public void publishAgentOutput(String teamAgentId, String subAgentId,
                                   String executionId, String instanceId,
                                   String nodeId, String nodeLabel,
                                   String agentStatus, String outputText,
                                   String tenantId) {
        try {
            String summary = outputText != null && outputText.length() > 2000
                    ? outputText.substring(0, 2000) + "...[截断]"
                    : outputText;

            AgentContextMessage message = AgentContextMessage.builder()
                    .teamAgentId(teamAgentId)
                    .subAgentId(subAgentId)
                    .executionId(executionId)
                    .instanceId(instanceId)
                    .nodeId(nodeId)
                    .nodeLabel(nodeLabel)
                    .messageType("agent_output")
                    .outputSummary(summary)
                    .agentStatus(agentStatus)
                    .tenantId(tenantId)
                    .publishedAt(LocalDateTime.now())
                    .build();

            // 路由键: agent.team.context.{teamAgentId}
            String routingKey = ROUTING_KEY_PREFIX + (teamAgentId != null ? teamAgentId : "global");
            rabbitTemplate.convertAndSend(EXCHANGE, routingKey, message);

            log.info("发布 Agent 产出消息: teamAgentId={}, nodeId={}, status={}", teamAgentId, nodeId, agentStatus);
        } catch (Exception e) {
            // MQ 发布失败不影响主流程
            log.warn("MQ 消息发布失败（不影响工作流推进）: nodeId={}, error={}", nodeId, e.getMessage());
        }
    }

    /**
     * 发布团队执行完成通知
     */
    public void publishTeamCompletion(String teamAgentId, String instanceId, String tenantId) {
        try {
            AgentContextMessage message = AgentContextMessage.builder()
                    .teamAgentId(teamAgentId)
                    .instanceId(instanceId)
                    .messageType("completion")
                    .tenantId(tenantId)
                    .publishedAt(LocalDateTime.now())
                    .build();

            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY_PREFIX + teamAgentId, message);
            log.info("发布团队完成通知: teamAgentId={}, instanceId={}", teamAgentId, instanceId);
        } catch (Exception e) {
            log.warn("团队完成通知发布失败: {}", e.getMessage());
        }
    }
}
