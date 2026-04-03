package com.schemaplexai.service.agent.execution;

import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.agent.runtime.AgentRuntimeOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;

/**
 * Agent执行调度消费者（RabbitMQ消息处理）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentExecuteDispatcher {

    private final AgentExecutionMapper agentExecutionMapper;
    private final AgentMapper agentMapper;
    private final AgentRuntimeOrchestrator agentRuntimeOrchestrator;
    private final ExecutionLeaseService executionLeaseService;
    private final ExecutionAdmissionService admissionService;
    private final AgentLogService agentLogService;

    @Value("${schemaplexai.execution.lease-seconds:120}")
    private int leaseSeconds;

    private final String nodeId = resolveNodeId();

    /**
     * 监听执行队列消息，消费后触发Agent执行
     */
    @RabbitListener(queues = "sf.agent.execute")
    public void onExecuteMessage(Map<String, Object> payload) {
        String executionId = payload != null ? asString(payload.get("executionId")) : null;
        if (!StringUtils.hasText(executionId)) {
            log.warn("执行消息缺少executionId，忽略");
            return;
        }

        AgentExecution execution = agentExecutionMapper.selectById(executionId);
        if (execution == null) {
            log.warn("执行记录不存在，忽略消息: executionId={}", executionId);
            return;
        }
        if (!AgentExecutionStatusEnum.QUEUED.getCode().equals(execution.getStatus())) {
            log.info("执行记录非queued状态，忽略: executionId={}, status={}", executionId, execution.getStatus());
            return;
        }

        Agent agent = agentMapper.selectById(execution.getAgentId());
        if (agent == null) {
            agentLogService.updateExecutionStatus(
                    executionId,
                    AgentExecutionStatusEnum.FAILED.getCode(),
                    "Agent不存在，无法调度执行",
                    null,
                    null,
                    null
            );
            return;
        }

        // 三维限流：租户 + Agent + 模型
        ExecutionAdmissionService.AdmissionToken admissionToken = null;
        try {
            admissionToken = admissionService.acquire(
                    execution.getTenantId(),
                    agent.getId(),
                    execution.getAiModel(),
                    executionId
            );
        } catch (BusinessException e) {
            log.info("执行准入被限流拒绝: executionId={}, tenantId={}, agentId={}, model={}",
                    executionId, execution.getTenantId(), agent.getId(), execution.getAiModel());
            return;
        }

        if (!executionLeaseService.acquireLease(executionId, nodeId, leaseSeconds)) {
            log.info("执行记录已被其他节点领取: executionId={}, nodeId={}", executionId, nodeId);
            admissionService.release(admissionToken);
            return;
        }

        AgentExecutionContext context = AgentExecutionContext.builder()
                .executionId(execution.getId())
                .agentId(execution.getAgentId())
                .tenantId(execution.getTenantId())
                .inputPrompt(execution.getInputPrompt())
                .inputContext(execution.getInputContext())
                .model(execution.getAiModel())
                .agentModelType(agent.getAiModelType())
                .agentModelGroupId(agent.getAiModelGroupId())
                .conversationId(execution.getConversationId())
                .stream(false)
                .build();

        // 获取准入令牌的副本用于 lambda 中的释放
        final ExecutionAdmissionService.AdmissionToken admissionTokenRef = admissionToken;
        try {
            agentRuntimeOrchestrator.execute(context).whenComplete((ignored, throwable) -> {
                executionLeaseService.releaseLease(executionId, nodeId);
                admissionService.release(admissionTokenRef);
                if (throwable != null) {
                    log.error("异步执行回调异常: executionId={}", executionId, throwable);
                }
            });
            log.info("执行任务已分发: executionId={}, nodeId={}", executionId, nodeId);
        } catch (Exception ex) {
            executionLeaseService.releaseLease(executionId, nodeId);
            admissionService.release(admissionToken);
            agentLogService.updateExecutionStatus(
                    executionId,
                    AgentExecutionStatusEnum.FAILED.getCode(),
                    ex.getMessage(),
                    null,
                    null,
                    null
            );
            log.error("分发执行失败: executionId={}", executionId, ex);
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String resolveNodeId() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (StringUtils.hasText(host)) {
                return host;
            }
        } catch (Exception ignored) {
            // ignore
        }
        return "node-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
