package com.schemaplexai.service.security.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.SecurityIncidentActionMapper;
import com.schemaplexai.dao.mapper.SecurityIncidentMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.SecurityIncident;
import com.schemaplexai.model.vo.security.SecurityAuditEventVO;
import com.schemaplexai.service.agent.runtime.AgentRuntimeOrchestrator;
import com.schemaplexai.service.security.SecurityAuditEventService;
import com.schemaplexai.service.workflow.WorkflowInstanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SecurityIncidentServiceImplTest {

    private final SecurityIncidentMapper incidents = mock(SecurityIncidentMapper.class);
    private final AgentExecutionMapper executions = mock(AgentExecutionMapper.class);
    private final AgentMapper agents = mock(AgentMapper.class);
    private final AgentRuntimeOrchestrator orchestrator = mock(AgentRuntimeOrchestrator.class);
    private final SecurityAuditEventService audit = mock(SecurityAuditEventService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AgentRuntimeOrchestrator> provider = mock(ObjectProvider.class);
    private final SecurityIncidentServiceImpl service = new SecurityIncidentServiceImpl(
            incidents, mock(SecurityIncidentActionMapper.class), audit,
            executions, agents, mock(WorkflowInstanceMapper.class), provider, mock(WorkflowInstanceService.class));

    @BeforeEach
    void setTenant() {
        SecurityUtil.setCurrentTenantId("tenant");
    }

    @AfterEach
    void clearTenant() {
        SecurityUtil.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldResumeSoloOrTeamParentForCurrentIncident(boolean team) {
        var execution = pausedExecution("source");
        var incident = stubIncident(execution);
        registerCurrentPause(execution, incident);
        when(audit.listByTraceId(incident.getTraceId())).thenReturn(List.of(
                pauseAudit(incident, SecurityComplianceConstant.CHECK_SCENE_TOOL_PRE)));
        String targetId = execution.getId();
        if (team) {
            var parent = pausedExecution("parent");
            targetId = parent.getId();
            execution.setParentExecutionId(targetId);
            when(executions.selectById(targetId)).thenReturn(parent);
        }

        var outcome = service.resumeResource(incident.getId(), null);

        var input = ArgumentCaptor.forClass(AgentExecutionInputDTO.class);
        verify(orchestrator).resume(eq(targetId), input.capture());
        assertThat(input.getValue().getSecurityPausedChildExecutionId()).isEqualTo(team ? execution.getId() : null);
        assertThat(input.getValue().getOptions()).containsEntry("incidentId", incident.getId());
        assertThat(input.getValue().getMessage()).contains("在派发前被安全规则暂停，尚未执行", "仍受当前安全规则和审批约束");
        assertThat(outcome.getStatus()).isEqualTo(SecurityComplianceConstant.INCIDENT_STATUS_INVESTIGATING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"post", "missing", "other-incident", "other-tenant", "other-resource", "other-source", "other-trace", "conflicting"})
    void shouldKeepConservativeRecoveryWithoutUnambiguousPreDispatchAudit(String auditCase) {
        var execution = pausedExecution("source");
        var incident = stubIncident(execution);
        registerCurrentPause(execution, incident);
        var event = pauseAudit(incident, SecurityComplianceConstant.CHECK_SCENE_TOOL_PRE);
        var post = pauseAudit(incident, SecurityComplianceConstant.CHECK_SCENE_TOOL_POST);
        List<SecurityAuditEventVO> evidence = switch (auditCase) {
            case "post" -> List.of(post);
            case "missing" -> List.of();
            case "conflicting" -> List.of(event, post);
            default -> List.of(event);
        };
        switch (auditCase) {
            case "other-incident" -> event.setMetadata(Map.of("incidentId", "different-incident", "decision",
                    SecurityComplianceConstant.DECISION_PAUSE, "scene", SecurityComplianceConstant.CHECK_SCENE_TOOL_PRE));
            case "other-tenant" -> event.setTenantId("different-tenant");
            case "other-resource" -> event.setResourceId("different-execution");
            case "other-source" -> event.setEventSource("different-source");
            case "other-trace" -> event.setTraceId("different-trace");
        }
        when(audit.listByTraceId(incident.getTraceId())).thenReturn(evidence);

        service.resumeResource(incident.getId(), null);

        var input = ArgumentCaptor.forClass(AgentExecutionInputDTO.class);
        verify(orchestrator).resume(eq(execution.getId()), input.capture());
        assertThat(input.getValue().getMessage()).contains("先核实当前状态，避免重复写入").doesNotContain("尚未执行");
    }

    @Test
    void shouldKeepAdmissionPauseOnFirstRoundPath() {
        var execution = pausedExecution("source");
        execution.setStartedAt(null);
        var incident = stubIncident(execution);
        registerCurrentPause(execution, incident);

        service.resumeResource(incident.getId(), null);

        var input = ArgumentCaptor.forClass(AgentExecutionInputDTO.class);
        verify(orchestrator).resume(eq(execution.getId()), input.capture());
        assertThat(input.getValue().isResumeBeforeFirstRound()).isTrue();
        assertThat(input.getValue().getMessage()).doesNotContain("尚未执行");
    }

    @Test
    void shouldRejectIncidentThatDoesNotOwnTheCurrentPause() {
        var execution = pausedExecution("source");
        var incident = stubIncident(execution);
        var newer = new SecurityIncident();
        newer.setId("newer");
        when(incidents.findExecutionPause(execution)).thenReturn(newer);

        assertThatThrownBy(() -> service.resumeResource(incident.getId(), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("与当前暂停不匹配");
        verifyNoInteractions(orchestrator);
    }

    @Test
    void shouldIgnoreUntrustedSecurityResumeTargetInRequestJson() throws Exception {
        var input = new ObjectMapper().readValue("""
                {"message":"继续","securityPausedChildExecutionId":"child","resumeBeforeFirstRound":true}
                """, AgentExecutionInputDTO.class);
        assertThat(input.getMessage()).isEqualTo("继续");
        assertThat(input.getSecurityPausedChildExecutionId()).isNull();
        assertThat(input.isResumeBeforeFirstRound()).isFalse();
    }

    private SecurityIncident stubIncident(AgentExecution execution) {
        var incident = new SecurityIncident();
        incident.setId("incident");
        incident.setIncidentNo("incident-number");
        incident.setTraceId("trace");
        incident.setTenantId("tenant");
        incident.setSourceType(SecurityComplianceConstant.RESOURCE_TYPE_AGENT_EXECUTION);
        incident.setSourceId(execution.getId());
        when(incidents.selectById(incident.getId())).thenReturn(incident);
        when(executions.selectById(execution.getId())).thenReturn(execution);
        return incident;
    }

    private void registerCurrentPause(AgentExecution execution, SecurityIncident incident) {
        when(incidents.findExecutionPause(execution)).thenReturn(incident);
        when(agents.selectById("agent")).thenReturn(new Agent());
        when(provider.getObject()).thenReturn(orchestrator);
    }

    private SecurityAuditEventVO pauseAudit(SecurityIncident incident, String scene) {
        var event = new SecurityAuditEventVO();
        event.setTenantId(incident.getTenantId());
        event.setTraceId(incident.getTraceId());
        event.setEventSource(SecurityComplianceConstant.AUDIT_SOURCE_RUNTIME_ENGINE);
        event.setEventType(SecurityComplianceConstant.EVENT_RUNTIME_CHECK);
        event.setResourceId(incident.getSourceId());
        event.setResourceType(SecurityComplianceConstant.RESOURCE_TYPE_AGENT_EXECUTION);
        event.setMetadata(Map.of("incidentId", incident.getId(), "decision", SecurityComplianceConstant.DECISION_PAUSE,
                "scene", scene));
        return event;
    }

    private AgentExecution pausedExecution(String id) {
        var execution = new AgentExecution();
        execution.setId(id);
        execution.setTenantId("tenant");
        execution.setAgentId("agent");
        execution.setCreatedBy("owner");
        execution.setStartedAt(LocalDateTime.of(2026, 11, 23, 23, 0));
        execution.setStatus(AgentExecutionStatusEnum.PAUSED.getCode());
        return execution;
    }
}
