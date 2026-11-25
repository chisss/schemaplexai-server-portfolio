package com.schemaplexai.service.evaluation.trajectory;

import com.schemaplexai.common.enums.AgentStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.model.dto.agent.AgentExecuteDTO;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;
import com.schemaplexai.service.agent.runtime.AgentRuntimeOrchestrator;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 评估场景 Agent 执行桥接
 * <p>
 * 为轨迹评估（TRAJECTORY 模式）驱动真实 Agentic Loop 执行：
 * 创建执行记录 → 调 {@link AgentRuntimeOrchestrator#execute} → 可控超时等待。
 * </p>
 * <p>
 * 与 orchestrator.executeSynchronously 的差异（评估场景专属）：
 * <ul>
 *   <li>超时可配置（默认 5 分钟），超时写 stopped 状态并返回结果而非抛异常——半程轨迹仍是"边界容错"评估素材</li>
 *   <li>工具审批保留引擎原生暂停语义（status=paused），不自动拒绝——审批行为本身是被评维度</li>
 *   <li>temporaryChat=true + memoryWriteEnabled=false，评估执行不污染会话记忆</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvalAgentExecutionBridge {

    /** 评估执行默认超时（秒） */
    private static final long DEFAULT_EVAL_TIMEOUT_SECONDS = 300;

    private final AgentMapper agentMapper;
    private final AgentExecutionMapper agentExecutionMapper;
    private final AgentRuntimeOrchestrator agentRuntimeOrchestrator;

    /**
     * 执行一次 Agent 评估并返回结果
     *
     * @param agentId 被测 Agent ID
     * @param inputPrompt 数据集条目的用户问题
     * @param tenantId 租户 ID（执行记录归属）
     * @param additionalSystemContexts 条目级附加系统上下文（metadata 配置下放：systemPrompt/context）
     */
    public EvalExecutionOutcome execute(String agentId, String inputPrompt, String tenantId,
                                        List<String> additionalSystemContexts) {
        Agent agent = requireActiveAgent(agentId);
        AgentExecuteDTO dto = buildExecuteDTO(inputPrompt);
        AgentExecution execution = insertExecutionRecord(agent, dto, tenantId);

        CompletableFuture<AgentExecutionResult> future = agentRuntimeOrchestrator.execute(
                AgentExecutionContext.builder()
                        .executionId(execution.getId())
                        .agentId(agent.getId())
                        .tenantId(execution.getTenantId())
                        .userId(execution.getCreatedBy())
                        .inputPrompt(dto.getPrompt())
                        .model(execution.getAiModel())
                        .agentModelType(agent.getAiModelType())
                        .agentModelGroupId(agent.getAiModelGroupId())
                        .inputContext(dto.getContext())
                        .conversationId(execution.getConversationId())
                        .reasoningStrength(dto.getReasoningStrength())
                        .skillCode(dto.getSkillCode())
                        .outputFormat(dto.getOutputFormat())
                        .additionalSystemContexts(additionalSystemContexts)
                        .stream(false)
                        .temporaryChat(true)
                        .memoryWriteEnabled(false)
                        .build());

        try {
            AgentExecutionResult result = future.get(DEFAULT_EVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return EvalExecutionOutcome.builder()
                    .executionId(execution.getId())
                    .conversationId(execution.getConversationId())
                    .status(result.getStatus())
                    .outputResult(result.getOutputResult())
                    .errorMessage(result.getErrorMessage())
                    .tokenInput(result.getTokenInput())
                    .tokenOutput(result.getTokenOutput())
                    .rounds(result.getRounds())
                    .timedOut(false)
                    .build();
        } catch (TimeoutException e) {
            future.cancel(true);
            markStopped(execution.getId(), "评估执行超时（" + DEFAULT_EVAL_TIMEOUT_SECONDS + "s）");
            log.warn("评估执行超时: agentId={}, executionId={}", agentId, execution.getId());
            return EvalExecutionOutcome.builder()
                    .executionId(execution.getId())
                    .conversationId(execution.getConversationId())
                    .status("stopped")
                    .errorMessage("评估执行超时（" + DEFAULT_EVAL_TIMEOUT_SECONDS + "s）")
                    .timedOut(true)
                    .build();
        } catch (Exception e) {
            log.warn("评估执行失败: agentId={}, executionId={}", agentId, execution.getId(), e);
            return EvalExecutionOutcome.builder()
                    .executionId(execution.getId())
                    .conversationId(execution.getConversationId())
                    .status("failed")
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private Agent requireActiveAgent(String agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BusinessException(ResultCode.AGENT_NOT_FOUND);
        }
        if (!AgentStatusEnum.ACTIVE.getCode().equals(agent.getStatus())) {
            throw new BusinessException(ResultCode.AGENT_INACTIVE, "Agent 未启用，无法参与评估");
        }
        return agent;
    }

    private AgentExecuteDTO buildExecuteDTO(String inputPrompt) {
        AgentExecuteDTO dto = new AgentExecuteDTO();
        dto.setPrompt(inputPrompt);
        dto.setTemporaryChat(true);
        dto.setMemoryWriteEnabled(false);
        dto.setStream(false);
        return dto;
    }

    private AgentExecution insertExecutionRecord(Agent agent, AgentExecuteDTO dto, String tenantId) {
        AgentExecution execution = new AgentExecution();
        execution.setTenantId(StringUtils.hasText(tenantId) ? tenantId : agent.getTenantId());
        execution.setAgentId(agent.getId());
        execution.setInputPrompt(dto.getPrompt());
        execution.setInputContext(dto.getContext());
        execution.setAiModel(StringUtils.hasText(dto.getModel()) ? dto.getModel() : agent.getAiModel());
        execution.setConversationId(StringUtils.hasText(dto.getConversationId())
                ? dto.getConversationId()
                : UUID.randomUUID().toString().replace("-", ""));
        execution.setStatus("queued");
        agentExecutionMapper.insert(execution);
        return execution;
    }

    private void markStopped(String executionId, String message) {
        AgentExecution update = new AgentExecution();
        update.setId(executionId);
        update.setStatus("stopped");
        update.setErrorMessage(message);
        update.setCompletedAt(java.time.LocalDateTime.now());
        agentExecutionMapper.updateById(update);
    }

    /**
     * 评估执行结果（含轨迹定位信息）
     */
    @Data
    @Builder
    public static class EvalExecutionOutcome {
        /** 执行 ID（轨迹采集入口） */
        private String executionId;
        /** 会话 ID */
        private String conversationId;
        /** 执行状态：completed / completed_with_fallback / paused / stopped / failed */
        private String status;
        /** 最终输出文本 */
        private String outputResult;
        /** 错误信息 */
        private String errorMessage;
        /** 总输入 Token */
        private long tokenInput;
        /** 总输出 Token */
        private long tokenOutput;
        /** 执行轮次 */
        private int rounds;
        /** 是否超时 */
        private boolean timedOut;
    }
}
