package com.schemaplexai.service.workflow.flowable;

import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.service.workflow.engine.WorkflowNodeEngine;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowEndEventListenerTest {

    @Test
    void shouldUseCurrentFlowableEndNodeIdWhenCompletingWorkflow() {
        WorkflowNodeEngine workflowNodeEngine = mock(WorkflowNodeEngine.class);
        WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
        DelegateExecution execution = mock(DelegateExecution.class);
        WorkflowInstance instance = new WorkflowInstance();
        EndEvent endEvent = new EndEvent();
        endEvent.setId("end1");

        when(execution.getVariable("sfInstanceId")).thenReturn("wf-1");
        when(execution.getId()).thenReturn("exec-1");
        when(execution.getCurrentActivityId()).thenReturn("end1");
        when(execution.getCurrentFlowElement()).thenReturn(endEvent);
        when(instanceMapper.selectById("wf-1")).thenReturn(instance);

        WorkflowEndEventListener listener = new WorkflowEndEventListener(workflowNodeEngine, instanceMapper);

        listener.notify(execution);

        verify(workflowNodeEngine).executeNodeSync(eq(instance), eq("end1"), anyMap());
    }

    @Test
    void shouldFallbackToFlowElementIdWhenCurrentActivityIdMissing() {
        WorkflowNodeEngine workflowNodeEngine = mock(WorkflowNodeEngine.class);
        WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
        DelegateExecution execution = mock(DelegateExecution.class);
        WorkflowInstance instance = new WorkflowInstance();
        EndEvent endEvent = new EndEvent();
        endEvent.setId("end-final");

        when(execution.getVariable("sfInstanceId")).thenReturn("wf-2");
        when(execution.getId()).thenReturn("exec-2");
        when(execution.getCurrentActivityId()).thenReturn(null);
        when(execution.getCurrentFlowElement()).thenReturn(endEvent);
        when(instanceMapper.selectById("wf-2")).thenReturn(instance);

        WorkflowEndEventListener listener = new WorkflowEndEventListener(workflowNodeEngine, instanceMapper);

        listener.notify(execution);

        verify(workflowNodeEngine).executeNodeSync(eq(instance), eq("end-final"), anyMap());
    }
}
