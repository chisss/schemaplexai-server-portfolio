package com.schemaplexai.service.mq;

import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.service.mq.message.WorkflowTriggerMessage;
import com.schemaplexai.service.workflow.WorkflowInstanceService;
import com.schemaplexai.service.workflow.runtime.SpecWorkflowRuntimeService;
import com.schemaplexai.model.vo.workflow.WorkflowInstanceVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowTriggerConsumerTest {

    @Test
    void shouldPatchWorkflowInstanceIdWithoutOverwritingWorkspaceIds() {
        SecurityUtil.clear();
        WorkflowInstanceService workflowInstanceService = mock(WorkflowInstanceService.class);
        WorkflowTemplateMapper workflowTemplateMapper = mock(WorkflowTemplateMapper.class);
        SpecMapper specMapper = mock(SpecMapper.class);
        WorkflowInstanceMapper workflowInstanceMapper = mock(WorkflowInstanceMapper.class);
        WorkflowNodeExecutionMapper workflowNodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        SpecWorkflowRuntimeService specWorkflowRuntimeService = mock(SpecWorkflowRuntimeService.class);

        WorkflowTriggerConsumer consumer = new WorkflowTriggerConsumer(
                workflowInstanceService,
                workflowTemplateMapper,
                specMapper,
                workflowInstanceMapper,
                workflowNodeExecutionMapper,
                specWorkflowRuntimeService
        );

        Spec spec = new Spec();
        spec.setName("工作流回归");
        spec.setWorkflowId("tpl-1");
        spec.setWorkspaceIds(List.of("workspace-1"));
        when(specMapper.selectById("spec-1")).thenReturn(spec);
        when(workflowInstanceMapper.selectList(any())).thenReturn(List.of());
        when(specWorkflowRuntimeService.buildRuntimeVariables(any(), any()))
                .thenReturn(Map.of("workflowGoal", "动态目标"));
        when(specWorkflowRuntimeService.resolveTargetBranch(spec)).thenReturn("feature/SPEC-1");

        WorkflowInstanceVO instance = new WorkflowInstanceVO();
        instance.setId("wf-1");
        when(workflowInstanceService.create(any())).thenAnswer(invocation -> {
            assertThat(SecurityUtil.getCurrentTenantId()).isEqualTo("tenant-1");
            assertThat(SecurityUtil.getCurrentUserId()).isEqualTo("user-1");
            return instance;
        });
        when(workflowInstanceService.start("wf-1")).thenAnswer(invocation -> {
            assertThat(SecurityUtil.getCurrentTenantId()).isEqualTo("tenant-1");
            assertThat(SecurityUtil.getCurrentUserId()).isEqualTo("user-1");
            return instance;
        });

        WorkflowTriggerMessage message = WorkflowTriggerMessage.builder()
                .specId("spec-1")
                .docType("requirements")
                .triggerType("spec-review")
                .workflowTemplateId("tpl-1")
                .tenantId("tenant-1")
                .triggerBy("user-1")
                .requestId("req-1")
                .triggeredAt(LocalDateTime.now())
                .build();

        consumer.handleWorkflowTrigger(message);

        ArgumentCaptor<Spec> updateCaptor = ArgumentCaptor.forClass(Spec.class);
        verify(specMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getWorkflowInstanceId()).isEqualTo("wf-1");
        assertThat(updateCaptor.getValue().getWorkspaceIds()).isNull();
        verify(workflowInstanceService).start("wf-1");
        assertThat(SecurityUtil.getCurrentTenantId()).isNull();
        assertThat(SecurityUtil.getCurrentUserId()).isNull();
    }
}
