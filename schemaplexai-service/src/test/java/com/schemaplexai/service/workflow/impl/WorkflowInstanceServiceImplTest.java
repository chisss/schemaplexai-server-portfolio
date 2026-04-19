package com.schemaplexai.service.workflow.impl;

import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.converter.WorkflowInstanceConverter;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowTemplate;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.model.vo.workflow.WorkflowInstanceVO;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import com.schemaplexai.service.workflow.engine.WorkflowNodeEngine;
import com.schemaplexai.service.workflow.flowable.FlowableWorkflowBridge;
import com.schemaplexai.service.workflow.validator.WorkflowValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowInstanceServiceImplTest {

    @Test
    void shouldNotOverwriteCompletedStateWhenFlowableFinishesSynchronously() {
        WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
        WorkflowTemplateMapper templateMapper = mock(WorkflowTemplateMapper.class);
        WorkflowNodeExecutionMapper nodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        WorkflowInstanceConverter instanceConverter = mock(WorkflowInstanceConverter.class);
        WorkflowValidator workflowValidator = mock(WorkflowValidator.class);
        WorkflowNodeEngine workflowNodeEngine = mock(WorkflowNodeEngine.class);
        FlowableWorkflowBridge flowableBridge = mock(FlowableWorkflowBridge.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SecurityRuntimeGuardService> securityProvider = mock(ObjectProvider.class);
        SecurityRuntimeGuardService securityRuntimeGuardService = mock(SecurityRuntimeGuardService.class);

        when(securityProvider.getObject()).thenReturn(securityRuntimeGuardService);
        SecurityCheckDecisionVO allowDecision = new SecurityCheckDecisionVO();
        allowDecision.setDecision(SecurityComplianceConstant.DECISION_ALLOW);
        when(securityRuntimeGuardService.evaluate(any(), any())).thenReturn(allowDecision);
        doNothing().when(workflowValidator).validateCanStart(any());
        doNothing().when(workflowNodeEngine).initNodeExecutionRecords(any());
        when(flowableBridge.startProcess(anyString(), anyString(), anyMap())).thenReturn("proc-1");
        when(nodeExecutionMapper.selectList(any())).thenReturn(List.of());

        WorkflowInstance initial = new WorkflowInstance();
        initial.setId("wf-1");
        initial.setTemplateId("tpl-1");
        initial.setTenantId("tenant-1");
        initial.setName("cron-run");
        initial.setStatus(WorkflowInstanceStatusEnum.PENDING.getCode());
        initial.setVariables(Map.of("triggerType", "cron"));

        WorkflowInstance latest = new WorkflowInstance();
        latest.setId("wf-1");
        latest.setTemplateId("tpl-1");
        latest.setTenantId("tenant-1");
        latest.setName("cron-run");
        latest.setStatus(WorkflowInstanceStatusEnum.COMPLETED.getCode());
        latest.setCurrentNodeId("end1");
        latest.setProcessInstanceId("proc-1");
        latest.setCompletedAt(LocalDateTime.now());
        latest.setVariables(Map.of("triggerType", "cron"));

        when(instanceMapper.selectById("wf-1")).thenReturn(initial, latest);

        WorkflowTemplate template = new WorkflowTemplate();
        template.setId("tpl-1");
        template.setProcessDefinitionId("pd-1");
        when(templateMapper.selectById("tpl-1")).thenReturn(template);

        when(instanceConverter.toVO(any(WorkflowInstance.class))).thenAnswer(invocation -> {
            WorkflowInstance source = invocation.getArgument(0);
            WorkflowInstanceVO vo = new WorkflowInstanceVO();
            vo.setId(source.getId());
            vo.setStatus(source.getStatus());
            vo.setCurrentNodeId(source.getCurrentNodeId());
            vo.setCompletedAt(source.getCompletedAt());
            return vo;
        });

        WorkflowInstanceServiceImpl service = new WorkflowInstanceServiceImpl(
                instanceMapper,
                templateMapper,
                nodeExecutionMapper,
                instanceConverter,
                workflowValidator,
                workflowNodeEngine,
                flowableBridge,
                securityProvider
        );

        WorkflowInstanceVO result = service.start("wf-1");

        ArgumentCaptor<WorkflowInstance> updateCaptor = ArgumentCaptor.forClass(WorkflowInstance.class);
        verify(instanceMapper, times(2)).updateById(updateCaptor.capture());
        WorkflowInstance processUpdate = updateCaptor.getAllValues().get(1);

        assertThat(processUpdate.getId()).isEqualTo("wf-1");
        assertThat(processUpdate.getProcessInstanceId()).isEqualTo("proc-1");
        assertThat(processUpdate.getStatus()).isNull();
        assertThat(processUpdate.getCompletedAt()).isNull();

        assertThat(result.getStatus()).isEqualTo(WorkflowInstanceStatusEnum.COMPLETED.getCode());
        assertThat(result.getCurrentNodeId()).isEqualTo("end1");
        assertThat(result.getCompletedAt()).isNotNull();
        verify(flowableBridge).startProcess(eq("pd-1"), eq("wf-1"), anyMap());
    }

    @Test
    void shouldReturnLatestCompletedStateWhenResumeRestartsPendingWorkflow() {
        WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
        WorkflowTemplateMapper templateMapper = mock(WorkflowTemplateMapper.class);
        WorkflowNodeExecutionMapper nodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        WorkflowInstanceConverter instanceConverter = mock(WorkflowInstanceConverter.class);
        WorkflowValidator workflowValidator = mock(WorkflowValidator.class);
        WorkflowNodeEngine workflowNodeEngine = mock(WorkflowNodeEngine.class);
        FlowableWorkflowBridge flowableBridge = mock(FlowableWorkflowBridge.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SecurityRuntimeGuardService> securityProvider = mock(ObjectProvider.class);

        doNothing().when(workflowValidator).validateStatusTransition(
                WorkflowInstanceStatusEnum.PAUSED.getCode(),
                WorkflowInstanceStatusEnum.RUNNING.getCode()
        );
        doNothing().when(workflowNodeEngine).initNodeExecutionRecords(any());
        when(flowableBridge.startProcess(anyString(), anyString(), anyMap())).thenReturn("proc-resume-1");
        when(nodeExecutionMapper.selectCount(any())).thenReturn(0L);
        when(nodeExecutionMapper.selectList(any())).thenReturn(List.of());

        WorkflowInstance paused = new WorkflowInstance();
        paused.setId("wf-resume-1");
        paused.setTemplateId("tpl-1");
        paused.setTenantId("tenant-1");
        paused.setName("security-resume");
        paused.setStatus(WorkflowInstanceStatusEnum.PAUSED.getCode());
        paused.setVariables(new java.util.HashMap<>(Map.of(
                "securityPendingStart", true,
                "dangerousCommand", "rm -rf /tmp/schemaplexai-e2e"
        )));

        WorkflowInstance latest = new WorkflowInstance();
        latest.setId("wf-resume-1");
        latest.setTemplateId("tpl-1");
        latest.setTenantId("tenant-1");
        latest.setName("security-resume");
        latest.setStatus(WorkflowInstanceStatusEnum.COMPLETED.getCode());
        latest.setCurrentNodeId("end1");
        latest.setCompletedAt(LocalDateTime.now());
        latest.setVariables(Map.of("dangerousCommand", "rm -rf /tmp/schemaplexai-e2e"));

        when(instanceMapper.selectById("wf-resume-1")).thenReturn(paused, latest);

        WorkflowTemplate template = new WorkflowTemplate();
        template.setId("tpl-1");
        template.setProcessDefinitionId("pd-1");
        when(templateMapper.selectById("tpl-1")).thenReturn(template);

        when(instanceConverter.toVO(any(WorkflowInstance.class))).thenAnswer(invocation -> {
            WorkflowInstance source = invocation.getArgument(0);
            WorkflowInstanceVO vo = new WorkflowInstanceVO();
            vo.setId(source.getId());
            vo.setStatus(source.getStatus());
            vo.setCurrentNodeId(source.getCurrentNodeId());
            vo.setCompletedAt(source.getCompletedAt());
            vo.setVariables(source.getVariables());
            return vo;
        });

        WorkflowInstanceServiceImpl service = new WorkflowInstanceServiceImpl(
                instanceMapper,
                templateMapper,
                nodeExecutionMapper,
                instanceConverter,
                workflowValidator,
                workflowNodeEngine,
                flowableBridge,
                securityProvider
        );

        WorkflowInstanceVO result = service.resume("wf-resume-1");

        ArgumentCaptor<WorkflowInstance> updateCaptor = ArgumentCaptor.forClass(WorkflowInstance.class);
        verify(instanceMapper, times(3)).updateById(updateCaptor.capture());
        WorkflowInstance processUpdate = updateCaptor.getAllValues().get(2);

        assertThat(processUpdate.getId()).isEqualTo("wf-resume-1");
        assertThat(processUpdate.getProcessInstanceId()).isEqualTo("proc-resume-1");
        assertThat(processUpdate.getStatus()).isNull();
        assertThat(processUpdate.getCompletedAt()).isNull();

        assertThat(result.getStatus()).isEqualTo(WorkflowInstanceStatusEnum.COMPLETED.getCode());
        assertThat(result.getCurrentNodeId()).isEqualTo("end1");
        assertThat(result.getCompletedAt()).isNotNull();
        assertThat(result.getVariables()).doesNotContainKey("securityPendingStart");
    }
}
