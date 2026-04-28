package com.schemaplexai.service.agent.execution.approval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.util.LinkedHashMap;
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

    private static final String DECISION_APPROVE = "approve";
    private static final String DECISION_APPROVE_ALWAYS = "approve_always";
    private static final String DECISION_DENY = "deny";
    private static final String DECISION_EDIT = "edit";

    private final PendingToolApprovalMapper approvalMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PendingToolApproval createPending(CreatePendingToolApprovalCommand command) {
        ToolExecutionRequest request = command.getToolRequest();
        PendingToolApproval approval = new PendingToolApproval();
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

    public PendingToolApproval decide(String approvalId, AgentExecutionInputDTO input, String decidedBy) {
        PendingToolApproval approval = approvalMapper.selectById(approvalId);
        if (approval == null) {
            throw new IllegalArgumentException("待审批工具调用不存在: " + approvalId);
        }
        String decision = input.getApprovalDecision();
        approval.setDecision(decision);
        approval.setDecisionReason(input.getDecisionReason());
        approval.setDecidedBy(decidedBy);
        approval.setDecidedAt(LocalDateTime.now());
        if (DECISION_DENY.equals(decision)) {
            approval.setDecisionStatus(STATUS_DENIED);
        } else if (DECISION_EDIT.equals(decision)) {
            approval.setDecisionStatus(STATUS_EDITED);
            if (input.getEditedArguments() != null && !input.getEditedArguments().isEmpty()) {
                approval.setToolArguments(new LinkedHashMap<>(input.getEditedArguments()));
                approval.setToolArgumentsText(toJson(input.getEditedArguments()));
            }
        } else if (DECISION_APPROVE.equals(decision) || DECISION_APPROVE_ALWAYS.equals(decision)) {
            approval.setDecisionStatus(STATUS_APPROVED);
        } else {
            throw new IllegalArgumentException("无效的审批决策: " + decision);
        }
        approvalMapper.updateById(approval);
        return approval;
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
