package com.schemaplexai.service.agent.execution.approval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.enums.ToolIoTypeEnum;
import com.schemaplexai.dao.mapper.PendingToolApprovalMapper;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.PendingToolApproval;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 待审批工具调用服务
 */
@Service
@RequiredArgsConstructor
public class PendingToolApprovalService {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_DENIED = "denied";
    public static final String STATUS_EDITED = "edited";
    public static final String STATUS_CONSUMED = "consumed";

    private static final String DECISION_APPROVE = "approve";
    private static final String DECISION_APPROVE_ALWAYS = "approve_always";
    private static final String DECISION_DENY = "deny";
    private static final String DECISION_EDIT = "edit";

    private final PendingToolApprovalMapper approvalMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PendingToolApproval createPending(CreatePendingToolApprovalCommand command) {
        ToolExecutionRequest request = command.getToolRequest();
        PendingToolApproval approval = new PendingToolApproval();
        approval.setBatchId(command.getBatchId());
        approval.setTenantId(command.getTenantId());
        approval.setAgentId(command.getAgentId());
        approval.setExecutionId(command.getExecutionId());
        approval.setConversationId(command.getConversationId());
        approval.setRoundNum(command.getRoundNum());
        approval.setToolCallId(request != null ? request.id() : null);
        approval.setToolCode(request != null ? request.name() : null);
        approval.setToolName(request != null ? request.name() : null);
        approval.setToolArgumentsText(request != null ? request.arguments() : null);
        approval.setToolArguments(parseArguments(approval.getToolArgumentsText()));
        ToolIoTypeEnum ioType = command.getIoType() != null ? command.getIoType() : ToolIoTypeEnum.READ_WRITE;
        approval.setIoType(ioType.getCode());
        approval.setRiskLevel(StringUtils.hasText(command.getRiskLevel()) ? command.getRiskLevel() : "MEDIUM");
        approval.setExecutionMode(StringUtils.hasText(command.getExecutionMode()) ? command.getExecutionMode() : "auto");
        approval.setDecisionStatus(STATUS_PENDING);
        approvalMapper.insert(approval);
        return approval;
    }

    /**
     * 决策（不做归属校验；仅当调用方无四元组上下文时使用）
     */
    public PendingToolApproval decide(String approvalId, AgentExecutionInputDTO input, String decidedBy) {
        return decide(approvalId, input, decidedBy, null, null, null, null);
    }

    /**
     * 归属校验 + PENDING 一次性状态迁移（原子 CAS，防重复决策导致重复执行写工具）。
     * expected* 为空时跳过对应校验；任一项不匹配即拒绝。
     */
    public PendingToolApproval decide(String approvalId, AgentExecutionInputDTO input, String decidedBy,
                                      String expectedTenantId, String expectedAgentId,
                                      String expectedExecutionId, String expectedConversationId) {
        PendingToolApproval approval = approvalMapper.selectById(approvalId);
        if (approval == null) {
            throw new IllegalArgumentException("待审批工具调用不存在: " + approvalId);
        }
        validateOwnership(approval, expectedTenantId, expectedAgentId, expectedExecutionId, expectedConversationId);
        String decision = input.getApprovalDecision();
        if (!isValidDecision(decision)) {
            throw new IllegalArgumentException("无效的审批决策: " + decision);
        }

        PendingToolApproval update = new PendingToolApproval();
        update.setDecision(decision);
        update.setDecisionReason(input.getDecisionReason());
        update.setDecidedBy(decidedBy);
        update.setDecidedAt(LocalDateTime.now());
        if (DECISION_DENY.equals(decision)) {
            update.setDecisionStatus(STATUS_DENIED);
        } else if (DECISION_EDIT.equals(decision)) {
            update.setDecisionStatus(STATUS_EDITED);
            if (input.getEditedArguments() != null && !input.getEditedArguments().isEmpty()) {
                update.setToolArguments(new LinkedHashMap<>(input.getEditedArguments()));
                update.setToolArgumentsText(toJson(input.getEditedArguments()));
            }
        } else {
            update.setDecisionStatus(STATUS_APPROVED);
        }

        int rows = approvalMapper.update(update, new LambdaUpdateWrapper<PendingToolApproval>()
                .eq(PendingToolApproval::getId, approvalId)
                .eq(PendingToolApproval::getDecisionStatus, STATUS_PENDING));
        if (rows != 1) {
            throw new IllegalArgumentException("审批已处理，请勿重复提交: " + approvalId);
        }
        // 回填内存对象，供调用方继续使用
        approval.setDecision(update.getDecision());
        approval.setDecisionReason(update.getDecisionReason());
        approval.setDecidedBy(update.getDecidedBy());
        approval.setDecidedAt(update.getDecidedAt());
        approval.setDecisionStatus(update.getDecisionStatus());
        if (update.getToolArguments() != null) {
            approval.setToolArguments(update.getToolArguments());
            approval.setToolArgumentsText(update.getToolArgumentsText());
        }
        return approval;
    }

    /**
     * 一次性消费：approved/edited → consumed（CAS）。返回 false 表示已消费，调用方不得重复执行工具。
     */
    public boolean consume(String approvalId) {
        PendingToolApproval update = new PendingToolApproval();
        update.setDecisionStatus(STATUS_CONSUMED);
        int rows = approvalMapper.update(update, new LambdaUpdateWrapper<PendingToolApproval>()
                .eq(PendingToolApproval::getId, approvalId)
                .in(PendingToolApproval::getDecisionStatus, STATUS_APPROVED, STATUS_EDITED));
        return rows == 1;
    }

    private void validateOwnership(PendingToolApproval approval,
                                   String expectedTenantId, String expectedAgentId,
                                   String expectedExecutionId, String expectedConversationId) {
        List<String> violations = new ArrayList<>();
        if (StringUtils.hasText(expectedTenantId) && !expectedTenantId.equals(approval.getTenantId())) {
            violations.add("tenantId");
        }
        if (StringUtils.hasText(expectedAgentId) && !expectedAgentId.equals(approval.getAgentId())) {
            violations.add("agentId");
        }
        if (StringUtils.hasText(expectedExecutionId) && !expectedExecutionId.equals(approval.getExecutionId())) {
            violations.add("executionId");
        }
        if (StringUtils.hasText(expectedConversationId) && !expectedConversationId.equals(approval.getConversationId())) {
            violations.add("conversationId");
        }
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("审批归属校验失败: " + String.join(",", violations));
        }
    }

    private boolean isValidDecision(String decision) {
        return DECISION_DENY.equals(decision) || DECISION_EDIT.equals(decision)
                || DECISION_APPROVE.equals(decision) || DECISION_APPROVE_ALWAYS.equals(decision);
    }

    public Optional<PendingToolApproval> findActivePending(String executionId) {
        if (!StringUtils.hasText(executionId)) {
            return Optional.empty();
        }
        PendingToolApproval approval = approvalMapper.selectOne(new LambdaQueryWrapper<PendingToolApproval>()
                .eq(PendingToolApproval::getExecutionId, executionId)
                .eq(PendingToolApproval::getDecisionStatus, STATUS_PENDING)
                .orderByDesc(PendingToolApproval::getCreatedAt)
                .last("LIMIT 1"));
        return Optional.ofNullable(approval);
    }

    public Optional<PendingToolApproval> findById(String approvalId) {
        if (!StringUtils.hasText(approvalId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(approvalMapper.selectById(approvalId));
    }

    public ToolExecutionRequest rebuildToolRequest(PendingToolApproval approval) {
        return ToolExecutionRequest.builder()
                .id(approval.getToolCallId())
                .name(approval.getToolCode())
                .arguments(resolveArgumentsText(approval))
                .build();
    }

    public boolean isApproved(PendingToolApproval approval) {
        return approval != null
                && (STATUS_APPROVED.equals(approval.getDecisionStatus()) || STATUS_EDITED.equals(approval.getDecisionStatus()));
    }

    public boolean isDenied(PendingToolApproval approval) {
        return approval != null && STATUS_DENIED.equals(approval.getDecisionStatus());
    }

    private Map<String, Object> parseArguments(String arguments) {
        if (!StringUtils.hasText(arguments)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(arguments, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of("raw", arguments);
        }
    }

    private String resolveArgumentsText(PendingToolApproval approval) {
        if (StringUtils.hasText(approval.getToolArgumentsText())) {
            return approval.getToolArgumentsText();
        }
        return toJson(approval.getToolArguments());
    }

    private String toJson(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (Exception ignored) {
            return "{}";
        }
    }
}
