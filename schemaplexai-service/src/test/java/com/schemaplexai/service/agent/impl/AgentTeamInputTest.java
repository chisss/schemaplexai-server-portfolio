package com.schemaplexai.service.agent.impl;

import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.AgentTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.SecurityIncidentMapper;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.PendingToolApproval;
import com.schemaplexai.model.entity.SecurityIncident;
import com.schemaplexai.service.agent.execution.approval.PendingToolApprovalService;
import com.schemaplexai.service.agent.execution.ExecutionEventStreamService;
import com.schemaplexai.service.agent.execution.steering.ExecutionSteeringApplicationService;
import com.schemaplexai.service.agent.runtime.AgentRuntimeOrchestrator;
import com.schemaplexai.service.agent.runtime.team.TeamChildApprovalService;
import com.schemaplexai.service.common.EntityValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import com.schemaplexai.service.agent.ontology.AgentOntologyRuntimeContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTeamInputTest {

    @Mock private AgentExecutionMapper executionMapper;
    @Mock private AgentMapper agentMapper;
    @Mock private SecurityIncidentMapper securityIncidents;
    @Mock private EntityValidator entityValidator;
    @Mock private AgentRuntimeOrchestrator orchestrator;
    @Mock private ExecutionSteeringApplicationService steeringService;
    @Mock private PendingToolApprovalService pendingApprovals;
    @Mock private TeamChildApprovalService teamApprovals;
    @Mock private ExecutionEventStreamService executionEvents;
    @Mock private AgentOntologyRuntimeContext ontologyContext;
    @InjectMocks private AgentServiceImpl service;

    @BeforeEach
    void setUp() {
        SecurityUtil.setCurrentTenantId("tenant");
        SecurityUtil.setCurrentUserId("owner");
        Agent team = new Agent();
        team.setAgentType(AgentTypeEnum.TEAM.getCode());
        when(entityValidator.requireExists(eq(agentMapper), eq("agent"), any())).thenReturn(team);
    }

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
    }

    @Test
    void shouldSteerRunningTeamWithoutResumingGraph() {
        stubExecution(AgentExecutionStatusEnum.RUNNING);

        service.submitExecutionInput("agent", "execution", textInput());

        verify(steeringService).steer("execution", "tenant", "补充要求");
        verifyNoInteractions(orchestrator);
    }

    @Test
    void shouldResumePausedTeam() {
        stubExecution(AgentExecutionStatusEnum.PAUSED);
        AgentExecutionInputDTO input = textInput();

        service.submitExecutionInput("agent", "execution", input);

        verify(orchestrator).resume("execution", input);
        verifyNoInteractions(steeringService);
    }

    @Test
    void shouldRejectTextWhileTeamChildIsPausedByEnterprisePolicy() {
        stubExecution(AgentExecutionStatusEnum.PAUSED);
        var child = new AgentExecution();
        child.setId("child");
        when(executionMapper.selectList(any())).thenReturn(List.of(child));
        when(securityIncidents.findExecutionPause(any())).thenAnswer(call ->
                child.equals(call.getArgument(0)) ? new SecurityIncident() : null);

        assertThatThrownBy(() -> service.submitExecutionInput("agent", "execution", textInput()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("企业风险策略");
        verifyNoInteractions(orchestrator, teamApprovals);
    }

    @Test
    void shouldRejectTextWhileTeamChildApprovalIsPending() {
        AgentExecution parent = stubExecution(AgentExecutionStatusEnum.PAUSED);
        stubTeamApproval(parent);

        assertThatThrownBy(() -> service.submitExecutionInput("agent", "execution", textInput()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("待审批工具");
        verifyNoInteractions(pendingApprovals, orchestrator);
    }

    @Test
    void shouldRejectApprovalThatDoesNotMatchTeamChild() {
        AgentExecution parent = stubExecution(AgentExecutionStatusEnum.PAUSED);
        stubTeamApproval(parent);
        AgentExecutionInputDTO input = textInput();
        input.setApprovalId("other");
        input.setApprovalDecision("deny");

        assertThatThrownBy(() -> service.submitExecutionInput("agent", "execution", input))
                .isInstanceOf(BusinessException.class).hasMessageContaining("当前待审批工具");
        verifyNoInteractions(pendingApprovals, orchestrator);
    }

    @Test
    void shouldDecideChildApprovalAndResumeParentGraph() {
        AgentExecution parent = stubExecution(AgentExecutionStatusEnum.PAUSED);
        var pending = stubTeamApproval(parent);
        AgentExecutionInputDTO input = textInput();
        input.setApprovalId("approval");
        input.setApprovalDecision("deny");
        when(executionMapper.selectById("child")).thenReturn(pending.execution());
        when(pendingApprovals.findById("approval")).thenReturn(Optional.of(pending.approval()));
        when(pendingApprovals.decide("approval", input, "owner", "tenant", "agent", "child", "child-conversation"))
                .thenReturn(pending.approval());

        service.submitExecutionInput("agent", "execution", input);

        verify(pendingApprovals).decide("approval", input, "owner", "tenant", "agent", "child", "child-conversation");
        verify(orchestrator).resume("execution", input);
    }

    @Test
    void shouldRouteApprovalSubmittedForChildToParentGraph() {
        AgentExecution childExecution = stubExecution(AgentExecutionStatusEnum.PAUSED);
        childExecution.setId("child");
        childExecution.setParentExecutionId("parent");
        var parent = new AgentExecution();
        parent.setId("parent");
        parent.setAgentId("agent");
        parent.setCreatedBy("owner");
        parent.setStatus(AgentExecutionStatusEnum.PAUSED.getCode());
        var pending = stubTeamApproval(parent);
        AgentExecutionInputDTO input = textInput();
        input.setApprovalId("approval");
        input.setApprovalDecision("deny");
        when(executionMapper.selectById("parent")).thenReturn(parent);
        when(executionMapper.selectById("child")).thenReturn(pending.execution());
        when(pendingApprovals.findById("approval")).thenReturn(Optional.of(pending.approval()));
        when(pendingApprovals.decide("approval", input, "owner", "tenant", "agent", "child", "child-conversation"))
                .thenReturn(pending.approval());

        service.submitExecutionInput("agent", "child", input);

        verify(orchestrator).resume("parent", input);
    }

    @ParameterizedTest
    @EnumSource(value = AgentExecutionStatusEnum.class, names = {
            "QUEUED", "COMPLETED", "COMPLETED_WITH_FALLBACK", "FAILED", "CANCELLED", "STOPPED"
    })
    void shouldRejectInputOutsideRunningOrPausedStates(AgentExecutionStatusEnum status) {
        stubExecution(status);

        assertThatThrownBy(() -> service.submitExecutionInput("agent", "execution", textInput()))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(orchestrator, steeringService);
    }

    private AgentExecution stubExecution(AgentExecutionStatusEnum status) {
        AgentExecution execution = new AgentExecution();
        execution.setId("execution");
        execution.setCreatedBy("owner");
        execution.setStatus(status.getCode());
        when(executionMapper.selectOne(any())).thenReturn(execution);
        return execution;
    }

    private TeamChildApprovalService.ChildApproval stubTeamApproval(AgentExecution parent) {
        var child = new AgentExecution();
        child.setId("child");
        child.setAgentId("agent");
        child.setConversationId("child-conversation");
        var approval = new PendingToolApproval();
        approval.setId("approval");
        approval.setToolCode("tool_search_tool");
        var pending = new TeamChildApprovalService.ChildApproval(child, approval);
        when(teamApprovals.findPending(parent)).thenReturn(Optional.of(pending));
        return pending;
    }

    private AgentExecutionInputDTO textInput() {
        AgentExecutionInputDTO input = new AgentExecutionInputDTO();
        input.setMessage("补充要求");
        return input;
    }
}
