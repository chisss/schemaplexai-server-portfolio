package com.schemaplexai.service.agent.execution.approval;

import com.schemaplexai.common.enums.ToolIoTypeEnum;
import com.schemaplexai.dao.mapper.PendingToolApprovalMapper;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.PendingToolApproval;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingToolApprovalServiceTest {

    private final PendingToolApprovalMapper approvalMapper = mock(PendingToolApprovalMapper.class);
    private final PendingToolApprovalService service = new PendingToolApprovalService(approvalMapper);

    @Test
    void shouldCreatePendingApprovalFromToolRequest() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("sys.write")
                .arguments("{\"path\":\"README.md\"}")
                .build();

        PendingToolApproval approval = service.createPending(CreatePendingToolApprovalCommand.builder()
                .tenantId("tenant-1")
                .agentId("agent-1")
                .executionId("exec-1")
                .conversationId("conv-1")
                .roundNum(2)
                .executionMode("plan")
                .ioType(ToolIoTypeEnum.WRITE)
                .toolRequest(request)
                .build());

        ArgumentCaptor<PendingToolApproval> captor = ArgumentCaptor.forClass(PendingToolApproval.class);
        verify(approvalMapper).insert(captor.capture());
        PendingToolApproval inserted = captor.getValue();
        assertThat(inserted.getTenantId()).isEqualTo("tenant-1");
        assertThat(inserted.getExecutionId()).isEqualTo("exec-1");
        assertThat(inserted.getToolCallId()).isEqualTo("call-1");
        assertThat(inserted.getToolCode()).isEqualTo("sys.write");
        assertThat(inserted.getToolArgumentsText()).isEqualTo("{\"path\":\"README.md\"}");
        assertThat(inserted.getToolArguments()).containsEntry("path", "README.md");
        assertThat(inserted.getDecisionStatus()).isEqualTo(PendingToolApprovalService.STATUS_PENDING);
        assertThat(approval).isSameAs(inserted);
    }

    @Test
    void shouldApprovePendingApprovalAndRebuildOriginalToolRequest() {
        PendingToolApproval approval = pendingApproval();
        when(approvalMapper.selectById("approval-1")).thenReturn(approval);

        AgentExecutionInputDTO input = new AgentExecutionInputDTO();
        input.setApprovalDecision("approve");
        input.setDecisionReason("确认写入");

        PendingToolApproval decided = service.decide("approval-1", input, "user-1");
        ToolExecutionRequest rebuilt = service.rebuildToolRequest(decided);

        assertThat(decided.getDecisionStatus()).isEqualTo(PendingToolApprovalService.STATUS_APPROVED);
        assertThat(decided.getDecision()).isEqualTo("approve");
        assertThat(decided.getDecisionReason()).isEqualTo("确认写入");
        assertThat(decided.getDecidedBy()).isEqualTo("user-1");
        assertThat(decided.getDecidedAt()).isNotNull();
        assertThat(rebuilt.id()).isEqualTo("call-1");
        assertThat(rebuilt.name()).isEqualTo("sys.write");
        assertThat(rebuilt.arguments()).isEqualTo("{\"path\":\"README.md\"}");
        verify(approvalMapper).updateById(approval);
    }

    @Test
    void shouldDenyPendingApproval() {
        PendingToolApproval approval = pendingApproval();
        when(approvalMapper.selectById("approval-1")).thenReturn(approval);

        AgentExecutionInputDTO input = new AgentExecutionInputDTO();
        input.setApprovalDecision("deny");
        input.setDecisionReason("不允许修改");

        PendingToolApproval decided = service.decide("approval-1", input, "user-1");

        assertThat(decided.getDecisionStatus()).isEqualTo(PendingToolApprovalService.STATUS_DENIED);
        assertThat(decided.getDecision()).isEqualTo("deny");
        assertThat(decided.getDecisionReason()).isEqualTo("不允许修改");
        verify(approvalMapper).updateById(approval);
    }

    private PendingToolApproval pendingApproval() {
        PendingToolApproval approval = new PendingToolApproval();
        approval.setId("approval-1");
        approval.setTenantId("tenant-1");
        approval.setAgentId("agent-1");
        approval.setExecutionId("exec-1");
        approval.setConversationId("conv-1");
        approval.setRoundNum(2);
        approval.setToolCallId("call-1");
        approval.setToolCode("sys.write");
        approval.setToolName("sys.write");
        approval.setToolArgumentsText("{\"path\":\"README.md\"}");
        approval.setIoType(ToolIoTypeEnum.WRITE.getCode());
        approval.setExecutionMode("plan");
        approval.setDecisionStatus(PendingToolApprovalService.STATUS_PENDING);
        return approval;
    }
}
