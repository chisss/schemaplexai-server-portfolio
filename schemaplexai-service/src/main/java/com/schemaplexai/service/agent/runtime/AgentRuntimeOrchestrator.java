package com.schemaplexai.service.agent.runtime;

import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.AgentRuntimeEngineEnum;
import com.schemaplexai.common.enums.AgentStatusEnum;
import com.schemaplexai.common.enums.AgentTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.model.dto.agent.AgentExecuteDTO;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentConfig;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.agent.execution.AgentAcceptance;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.agent.execution.SandboxPolicyResolver;
import com.schemaplexai.service.agent.handler.AgentConfigHandler;
import com.schemaplexai.service.desktop.DesktopExecutionTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.UUID;

/**
 * Agent 运行时统一编排入口
 */
@Service
@RequiredArgsConstructor
public class AgentRuntimeOrchestrator {

    private static final String DESKTOP_VERIFICATION_CONFIG = "desktop_required_verification_command";
    private static final String DESKTOP_VERIFICATION_SNAPSHOT = "desktopVerificationCommand";

    private final AgentMapper agentMapper;
    private final AgentExecutionMapper agentExecutionMapper;
    private final AgentRuntimeStrategyFactory strategyFactory;
    private final SandboxPolicyResolver sandboxPolicyResolver;
    private final AgentConfigHandler agentConfigHandler;

    public CompletableFuture<AgentExecutionResult> execute(AgentExecutionContext context) {
        AgentExecution execution = requireExecution(context.getExecutionId());
        Agent agent = requireAgent(context.getAgentId());
        AgentRuntimeEngineEnum runtimeEngine = resolveRuntimeEngine(agent);
        context.setRuntimeEngine(runtimeEngine.getCode());
        context.setProjectId(execution.getMemoryProjectId());
        if (execution.getTemporaryChat() != null) {
            // 新执行优先使用创建时已保存的策略。
            context.setTemporaryChat(Boolean.TRUE.equals(execution.getTemporaryChat()));
            context.setMemoryWriteEnabled(execution.getMemoryWriteEnabled());
        }
        List<AgentConfig> configs = agentConfigHandler.loadConfigs(agent.getId());
        applyOutputFormatContext(configs, context);
        context.setDesktopVerificationCommand(DesktopExecutionTarget.from(context.getInputContext()) == null
                ? null : configValue(configs, DESKTOP_VERIFICATION_CONFIG));
        SandboxPolicy sandboxPolicy = sandboxPolicyResolver.resolve(agent, context);
        context.setSandboxPolicy(sandboxPolicy);

        AgentExecution update = new AgentExecution();
        update.setId(execution.getId());
        update.setRuntimeEngine(runtimeEngine.getCode());
        if (execution.getTemporaryChat() == null) {
            // 内部首次执行入口可能先创建没有策略快照的 queued 记录。
            update.setTemporaryChat(context.isTemporaryChat());
            update.setMemoryWriteEnabled(context.getMemoryWriteEnabled());
        }
        update.setSandboxPolicySnapshot(buildRuntimeSnapshot(context, sandboxPolicy));
        agentExecutionMapper.updateById(update);

        return strategyFactory.getStrategy(agent.getAgentType()).execute(agent, execution, context);
    }

    public CompletableFuture<AgentExecutionResult> recover(String executionId) {
        AgentExecution execution = requireExecution(executionId);
        Agent agent = requireAgent(execution.getAgentId());
        if (!AgentExecutionStatusEnum.RUNNING.getCode().equals(execution.getStatus())
                || StringUtils.hasText(execution.getParentExecutionId())
                || resolveRuntimeEngine(agent) != AgentRuntimeEngineEnum.SOLO_LANGCHAIN4J) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "只有运行中断的根 Solo 执行支持崩溃续跑");
        }
        if (!AgentStatusEnum.ACTIVE.getCode().equals(agent.getStatus())) {
            throw new BusinessException(ResultCode.AGENT_INACTIVE);
        }
        Map<String, Object> snapshot = execution.getSandboxPolicySnapshot();
        if (snapshot == null || execution.getTemporaryChat() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "执行缺少可恢复的策略快照");
        }
        AgentExecutionContext context = AgentExecutionContext.builder()
                .executionId(execution.getId())
                .agentId(execution.getAgentId())
                .tenantId(execution.getTenantId())
                .userId(execution.getCreatedBy())
                .projectId(execution.getMemoryProjectId())
                .inputPrompt(execution.getInputPrompt())
                .model(execution.getAiModel())
                .agentModelType(agent.getAiModelType())
                .agentModelGroupId(agent.getAiModelGroupId())
                .inputContext(execution.getInputContext())
                .conversationId(execution.getConversationId())
                .runtimeEngine(AgentRuntimeEngineEnum.SOLO_LANGCHAIN4J.getCode())
                .executionMode(execution.getExecutionMode())
                .desktopVerificationCommand(snapshotText(snapshot, DESKTOP_VERIFICATION_SNAPSHOT))
                .reasoningStrength(snapshotText(snapshot, "reasoningStrength"))
                .skillCode(snapshotText(snapshot, "skillCode"))
                .outputFormat(snapshotText(snapshot, "outputFormat"))
                .stream(Boolean.TRUE.equals(snapshot.get("stream")))
                .temporaryChat(Boolean.TRUE.equals(execution.getTemporaryChat()))
                .memoryWriteEnabled(execution.getMemoryWriteEnabled())
                .systemAgent(Boolean.TRUE.equals(agent.getIsSystemAgent()))
                .acceptance(AgentAcceptance.fromSnapshot(snapshot))
                .build();
        SandboxPolicy policy = sandboxPolicyResolver.resolve(agent, context);
        Map<String, Object> currentPolicy = sandboxPolicyResolver.toSnapshot(policy);
        if (currentPolicy.isEmpty() || currentPolicy.entrySet().stream().anyMatch(
                entry -> !samePolicyValue(entry.getValue(), snapshot.get(entry.getKey())))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "执行沙箱策略已变更，需先核对中断任务");
        }
        context.setSandboxPolicy(policy);
        return strategyFactory.getStrategy(agent.getAgentType()).recover(context);
    }

    public CompletableFuture<AgentExecutionResult> resume(String executionId, AgentExecutionInputDTO input) {
        AgentExecution execution = requireExecution(executionId);
        Agent agent = requireAgent(execution.getAgentId());
        AgentRuntimeEngineEnum runtimeEngine = resolveRuntimeEngine(agent);
        // 直接恢复子执行时也遵守父执行的限制。历史挂起记录沿用最严格的设置。
        AgentExecution parent = StringUtils.hasText(execution.getParentExecutionId())
                ? requireExecution(execution.getParentExecutionId()) : execution;
        boolean legacyPolicy = execution.getTemporaryChat() == null || parent.getTemporaryChat() == null;
        boolean temporaryChat = legacyPolicy || Boolean.TRUE.equals(execution.getTemporaryChat())
                || Boolean.TRUE.equals(parent.getTemporaryChat()) || Boolean.TRUE.equals(input.getTemporaryChat());
        boolean restrictWrite = legacyPolicy || Boolean.FALSE.equals(parent.getMemoryWriteEnabled())
                || Boolean.FALSE.equals(input.getMemoryWriteEnabled());
        Boolean memoryWriteEnabled = restrictWrite ? Boolean.FALSE : execution.getMemoryWriteEnabled();
        AgentExecutionContext context = AgentExecutionContext.builder()
                .executionId(execution.getId())
                .agentId(execution.getAgentId())
                .tenantId(execution.getTenantId())
                .userId(execution.getCreatedBy())
                .projectId(parent.getMemoryProjectId())
                .inputPrompt(execution.getInputPrompt())
                .model(execution.getAiModel())
                .agentModelType(agent.getAiModelType())
                .agentModelGroupId(agent.getAiModelGroupId())
                .inputContext(execution.getInputContext())
                .conversationId(execution.getConversationId())
                .runtimeEngine(runtimeEngine.getCode())
                .acceptance(AgentAcceptance.fromSnapshot(execution.getSandboxPolicySnapshot()))
                .stream(execution.getSandboxPolicySnapshot() != null
                        && Boolean.TRUE.equals(execution.getSandboxPolicySnapshot().get("stream")))
                .executionMode(execution.getExecutionMode() != null ? execution.getExecutionMode() : "auto")
                .systemAgent(Boolean.TRUE.equals(agent.getIsSystemAgent()))
                .temporaryChat(temporaryChat)
                .memoryWriteEnabled(memoryWriteEnabled)
                .build();
        applyOutputFormatContext(agentConfigHandler.loadConfigs(agent.getId()), context);
        if (parent.getSandboxPolicySnapshot() != null
                && parent.getSandboxPolicySnapshot().get(DESKTOP_VERIFICATION_SNAPSHOT) instanceof String command) {
            context.setDesktopVerificationCommand(command);
        }
        context.setParentExecutionId(execution.getParentExecutionId());
        // 恢复路径同样解析沙箱策略并注入上下文，策略实现不得重建上下文（审计 H2）
        SandboxPolicy sandboxPolicy = sandboxPolicyResolver.resolve(agent, context);
        context.setSandboxPolicy(sandboxPolicy);
        AgentExecution update = new AgentExecution();
        update.setId(execution.getId());
        update.setRuntimeEngine(runtimeEngine.getCode());
        // 恢复只提交更严格的值，较旧的恢复请求不能反向覆盖。
        if (temporaryChat) {
            update.setTemporaryChat(true);
        }
        if (Boolean.FALSE.equals(memoryWriteEnabled)) {
            update.setMemoryWriteEnabled(false);
        }
        update.setSandboxPolicySnapshot(buildRuntimeSnapshot(context, sandboxPolicy));
        agentExecutionMapper.updateById(update);
        return strategyFactory.getStrategy(agent.getAgentType()).resume(agent, execution, context, input);
    }

    private boolean samePolicyValue(Object current, Object previous) {
        if (current instanceof Collection<?> left && previous instanceof Collection<?> right) {
            return new HashSet<>(left).equals(new HashSet<>(right));
        }
        if (current instanceof Number left && previous instanceof Number right) {
            return left.longValue() == right.longValue();
        }
        return Objects.equals(current, previous);
    }

    private String snapshotText(Map<String, Object> snapshot, String key) {
        return snapshot.get(key) instanceof String value ? value : null;
    }

    private Map<String, Object> buildRuntimeSnapshot(AgentExecutionContext context, SandboxPolicy sandboxPolicy) {
        Map<String, Object> snapshot = new LinkedHashMap<>(sandboxPolicyResolver.toSnapshot(sandboxPolicy));
        if (context.isStream()) {
            snapshot.put("stream", true);
        }
        if (context.getBudgetAdmission() != null) {
            snapshot.put("budgetAdmission", context.getBudgetAdmission());
        }
        if (context.getDesktopVerificationCommand() != null) {
            snapshot.put(DESKTOP_VERIFICATION_SNAPSHOT, context.getDesktopVerificationCommand());
        }
        snapshot.put("reasoningStrength", context.getReasoningStrength());
        snapshot.put("skillCode", context.getSkillCode());
        snapshot.put("outputFormat", context.getOutputFormat());
        if (context.getAcceptance() != null) {
            snapshot.put(AgentAcceptance.SNAPSHOT_KEY, context.getAcceptance().requiredJson());
        }
        return snapshot;
    }

    /**
     * 同步执行 Agent，供工作流 AI 编排等嵌入式场景复用
     */
    public AgentExecutionResult executeSynchronously(Agent agent, AgentExecuteDTO dto) {
        if (agent == null || !StringUtils.hasText(agent.getId())) {
            throw new BusinessException(ResultCode.AGENT_NOT_FOUND);
        }
        AgentAcceptance acceptance = validateAcceptance(dto);
        AgentExecution execution = new AgentExecution();
        execution.setTenantId(StringUtils.hasText(agent.getTenantId()) ? agent.getTenantId() : SecurityUtil.getCurrentTenantId());
        execution.setAgentId(agent.getId());
        execution.setInputPrompt(dto.getPrompt());
        execution.setInputContext(dto.getContext());
        execution.setTemporaryChat(Boolean.TRUE.equals(dto.getTemporaryChat()));
        execution.setMemoryWriteEnabled(dto.getMemoryWriteEnabled());
        execution.setAiModel(StringUtils.hasText(dto.getModel()) ? dto.getModel() : agent.getAiModel());
        execution.setConversationId(StringUtils.hasText(dto.getConversationId())
                ? dto.getConversationId()
                : UUID.randomUUID().toString().replace("-", ""));
        execution.setStatus("queued");
        if (acceptance != null) {
            execution.setAcceptanceStatus(AgentAcceptance.Status.UNVERIFIED.code());
        }
        agentExecutionMapper.insert(execution);

        CompletableFuture<AgentExecutionResult> future = execute(AgentExecutionContext.builder()
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
                .acceptance(acceptance)
                .attachmentIds(dto.getAttachmentIds())
                .reasoningStrength(dto.getReasoningStrength())
                .skillCode(dto.getSkillCode())
                .outputFormat(dto.getOutputFormat())
                .stream(Boolean.TRUE.equals(dto.getStream()))
                .temporaryChat(Boolean.TRUE.equals(execution.getTemporaryChat()))
                .memoryWriteEnabled(execution.getMemoryWriteEnabled())
                .build());
        try {
            return future.get(90, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            // 取消等待者，并复用停止机制：写 stopped 状态后，后台任务在轮边界
            // isStopped 检查处自动退出（buildStoppedResult），不再继续产生副作用，
            // 也避免后台完成后用 COMPLETED 覆盖超时状态。
            future.cancel(true);
            AgentExecution update = new AgentExecution();
            update.setId(execution.getId());
            update.setStatus("stopped");
            update.setErrorMessage("工作流编排 Agent 执行超时");
            agentExecutionMapper.updateById(update);
            throw new BusinessException(ResultCode.AI_MODEL_TIMEOUT, "工作流编排 Agent 执行超时");
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.FAIL, "工作流编排 Agent 执行失败: " + exception.getMessage());
        }
    }

    public static AgentAcceptance validateAcceptance(AgentExecuteDTO dto) {
        if (dto.getRequiredOutputJson() == null) {
            return null;
        }
        try {
            return new AgentAcceptance(dto.getRequiredOutputJson());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ResultCode.FAIL, exception.getMessage());
        }
    }

    private AgentRuntimeEngineEnum resolveRuntimeEngine(Agent agent) {
        return AgentTypeEnum.TEAM == AgentTypeEnum.fromCode(agent.getAgentType())
                ? AgentRuntimeEngineEnum.TEAM_LANGGRAPH4J
                : AgentRuntimeEngineEnum.SOLO_LANGCHAIN4J;
    }

    private Agent requireAgent(String agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BusinessException(ResultCode.AGENT_NOT_FOUND);
        }
        return agent;
    }

    private AgentExecution requireExecution(String executionId) {
        AgentExecution execution = agentExecutionMapper.selectById(executionId);
        if (execution == null) {
            throw new BusinessException(ResultCode.AGENT_EXECUTION_NOT_FOUND);
        }
        return execution;
    }

    private String configValue(List<AgentConfig> configs, String key) {
        return configs.stream()
                .filter(config -> key.equals(config.getConfigKey()))
                .map(AgentConfig::getConfigValue)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private void applyOutputFormatContext(List<AgentConfig> configs, AgentExecutionContext context) {
        List<String> additionalContexts = new ArrayList<>(
                context.getAdditionalSystemContexts() == null ? List.of() : context.getAdditionalSystemContexts()
        );
        String outputFormat = configValue(configs, "output_format");
        if (!StringUtils.hasText(outputFormat)) {
            context.setAdditionalSystemContexts(additionalContexts);
            return;
        }
        switch (outputFormat.trim()) {
            case "markdown" -> additionalContexts.add("""
输出格式要求：
1. 请始终使用 Markdown 输出。
2. 使用清晰的标题、列表、表格或代码块组织内容。
3. 除非任务明确要求，不要输出 JSON。
""".trim());
            case "structured_json" -> additionalContexts.add("""
输出格式要求：
1. 请严格输出单个 JSON 对象。
2. 不要添加 Markdown 标题、代码块围栏或额外说明文字。
3. JSON 字段名使用英文小写下划线风格。
""".trim());
            case "plain_text" -> additionalContexts.add("""
输出格式要求：
1. 请使用纯文本输出。
2. 不要使用 Markdown 标题、表格或代码块围栏。
3. 内容保持简洁直接。
""".trim());
            default -> {
                // 未识别的配置值直接忽略，保持兼容。
            }
        }
        context.setAdditionalSystemContexts(additionalContexts);
    }
}
