package com.schemaplexai.service.agent.runtime.team;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.PendingToolApproval;
import com.schemaplexai.service.agent.execution.approval.PendingToolApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TeamChildApprovalService {

    public static final List<String> ALLOWED_DECISIONS = List.of("approve", "approve_always", "deny");

    private final AgentExecutionMapper executionMapper;
    private final PendingToolApprovalService approvals;

    public Optional<ChildApproval> findPending(AgentExecution parent) {
        if (!hasParentScope(parent)) {
            return Optional.empty();
        }
        return executionMapper.selectList(new LambdaQueryWrapper<AgentExecution>()
                        .eq(AgentExecution::getTenantId, parent.getTenantId())
                        .eq(AgentExecution::getAgentId, parent.getAgentId())
                        .eq(AgentExecution::getParentExecutionId, parent.getId())
                        .eq(AgentExecution::getStatus, AgentExecutionStatusEnum.PAUSED.getCode())
                        .orderByAsc(AgentExecution::getCreatedAt))
                .stream()
                .filter(child -> isPausedChild(parent, child))
                .map(child -> approvals.findActivePending(child.getId())
                        .filter(approval -> ownsApproval(child, approval))
                        .map(approval -> new ChildApproval(child, approval)))
                .flatMap(Optional::stream)
                .findFirst();
    }

    Optional<ChildApproval> findDecided(AgentExecution parent, AgentExecutionInputDTO input) {
        if (!hasParentScope(parent) || input == null || !StringUtils.hasText(input.getApprovalId())
                || !StringUtils.hasText(input.getApprovalDecision())) {
            return Optional.empty();
        }
        return approvals.findById(input.getApprovalId())
                .filter(approval -> input.getApprovalDecision().equals(approval.getDecision()))
                .filter(approval -> approvals.isApproved(approval) || approvals.isDenied(approval))
                .flatMap(approval -> Optional.ofNullable(executionMapper.selectById(approval.getExecutionId()))
                        .filter(child -> isPausedChild(parent, child) && ownsApproval(child, approval))
                        .map(child -> new ChildApproval(child, approval)));
    }

    private boolean hasParentScope(AgentExecution parent) {
        return StringUtils.hasText(parent.getId())
                && StringUtils.hasText(parent.getTenantId())
                && StringUtils.hasText(parent.getAgentId())
                && parent.getTenantId().equals(SecurityUtil.getCurrentTenantId())
                && !StringUtils.hasText(parent.getParentExecutionId());
    }

    private boolean isPausedChild(AgentExecution parent, AgentExecution child) {
        return parent.getId().equals(child.getParentExecutionId())
                && parent.getTenantId().equals(child.getTenantId())
                && parent.getAgentId().equals(child.getAgentId())
                && Objects.equals(parent.getCreatedBy(), child.getCreatedBy())
                && StringUtils.hasText(child.getTeamMemberId())
                && AgentExecutionStatusEnum.PAUSED.getCode().equals(child.getStatus());
    }

    private boolean ownsApproval(AgentExecution child, PendingToolApproval approval) {
        return child.getTenantId().equals(approval.getTenantId())
                && child.getId().equals(approval.getExecutionId())
                && child.getAgentId().equals(approval.getAgentId())
                && StringUtils.hasText(child.getConversationId())
                && child.getConversationId().equals(approval.getConversationId());
    }

    public record ChildApproval(AgentExecution execution, PendingToolApproval approval) {}
}
