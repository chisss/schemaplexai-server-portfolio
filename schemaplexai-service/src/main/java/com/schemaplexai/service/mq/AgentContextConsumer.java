package com.schemaplexai.service.mq;

import com.schemaplexai.dao.mapper.TeamAgentOutputMapper;
import com.schemaplexai.model.entity.TeamAgentOutput;
import com.schemaplexai.service.context.ContextCacheService;
import com.schemaplexai.service.mq.message.AgentContextMessage;
import com.schemaplexai.service.vector.MilvusVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Agent 上下文共享 MQ 消费者
 *
 * <p>消费 {@code sf.agent.team.context} 队列的消息，执行：
 * <ol>
 *   <li>写入 Redis 团队共享上下文 Hash（subAgentId → summary）</li>
 *   <li>持久化到 {@code sf_team_agent_output} 表</li>
 *   <li>（可选）异步触发 Milvus 向量索引（仅 agent_output 类型）</li>
 * </ol>
 *
 * <p>Milvus 索引为可选增强，不可用时静默跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentContextConsumer {

    private final ContextCacheService contextCacheService;
    private final TeamAgentOutputMapper teamAgentOutputMapper;

    /** Milvus 可选注入（Milvus 未启动时为 null） */
    @Lazy
    @Autowired(required = false)
    private MilvusVectorService milvusVectorService;

    @RabbitListener(queues = "sf.agent.team.context")
    public void onAgentContextMessage(AgentContextMessage message) {
        if (message == null) return;

        log.info("收到 Agent 上下文消息: type={}, teamAgentId={}, nodeId={}",
                message.getMessageType(), message.getTeamAgentId(), message.getNodeId());

        try {
            switch (message.getMessageType() != null ? message.getMessageType() : "") {
                case "agent_output" -> handleAgentOutput(message);
                case "completion" -> handleTeamCompletion(message);
                default -> log.debug("未处理的消息类型: {}", message.getMessageType());
            }
        } catch (Exception e) {
            log.error("消费 Agent 上下文消息异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理 Sub-Agent 产出消息
     */
    private void handleAgentOutput(AgentContextMessage message) {
        String teamAgentId = message.getTeamAgentId();
        String subAgentId = message.getSubAgentId();
        String summary = message.getOutputSummary();

        if (!StringUtils.hasText(summary)) return;

        // 1. 写入 Redis 团队共享上下文
        if (StringUtils.hasText(teamAgentId) && StringUtils.hasText(subAgentId)) {
            String contextEntry = String.format("[%s] %s",
                    message.getNodeLabel() != null ? message.getNodeLabel() : subAgentId,
                    summary);
            contextCacheService.updateTeamSharedContext(teamAgentId, subAgentId, contextEntry);
        }

        // 2. 持久化到 sf_team_agent_output
        persistTeamAgentOutput(message);

        // 3. 异步触发 Milvus 向量索引（输出摘要作为知识片段）
        if (milvusVectorService != null && StringUtils.hasText(summary)) {
            indexOutputToMilvus(message, summary);
        }
    }

    /**
     * 处理团队执行完成通知（清理 Redis 共享上下文 TTL 延长）
     */
    private void handleTeamCompletion(AgentContextMessage message) {
        log.info("团队执行完成通知: teamAgentId={}, instanceId={}",
                message.getTeamAgentId(), message.getInstanceId());
        // 完成后 Redis 缓存自然过期，无需主动删除
    }

    /**
     * 持久化 Sub-Agent 产出到 sf_team_agent_output
     */
    private void persistTeamAgentOutput(AgentContextMessage message) {
        try {
            TeamAgentOutput output = new TeamAgentOutput();
            output.setId(UUID.randomUUID().toString().replace("-", ""));
            output.setTenantId(message.getTenantId());
            output.setTeamAgentId(message.getTeamAgentId());
            output.setTeamExecutionId(message.getInstanceId());
            output.setSubAgentId(message.getSubAgentId());
            output.setRole("sub");
            output.setOutputType("summary");
            output.setOutputContent(message.getOutputSummary());
            output.setCompletedAt(message.getPublishedAt() != null
                    ? message.getPublishedAt() : LocalDateTime.now());
            teamAgentOutputMapper.insert(output);
        } catch (Exception e) {
            log.warn("持久化团队产出失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * 异步将 Agent 产出摘要索引到 Milvus
     * （作为动态知识片段，后续执行中可通过语义检索命中）
     */
    @Async("agentExecutorPool")
    protected void indexOutputToMilvus(AgentContextMessage message, String summary) {
        try {
            // 复用 executionId 作为向量主键，兼容历史集合对主键长度的限制
            boolean indexed = milvusVectorService.upsertContextItem(
                    message.getExecutionId(),
                    message.getTenantId(),
                    message.getTeamAgentId(),
                    message.getInstanceId(),  // 用 instanceId 作为 contextId
                    summary
            );
            if (indexed) {
                log.debug("Agent产出已索引到 Milvus: executionId={}", message.getExecutionId());
            } else {
                log.debug("Agent产出跳过 Milvus 索引: executionId={}", message.getExecutionId());
            }
        } catch (Exception e) {
            log.warn("Milvus 索引失败（不影响主流程）: {}", e.getMessage());
        }
    }
}
