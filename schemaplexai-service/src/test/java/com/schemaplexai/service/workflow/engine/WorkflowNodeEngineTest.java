package com.schemaplexai.service.workflow.engine;

import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.service.agent.runtime.AgentRuntimeOrchestrator;
import com.schemaplexai.service.mq.AgentContextPublisher;
import com.schemaplexai.service.quality.runtime.BuiltinQualityAssuranceService;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import com.schemaplexai.service.workflow.engine.assembler.WorkflowNodeContextAssembler;
import com.schemaplexai.service.workflow.engine.handler.DeviationAnalysisHandler;
import com.schemaplexai.service.workflow.engine.handler.QualityReportHandler;
import com.schemaplexai.service.workflow.runtime.WorkflowArtifactService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowNodeEngineTest {

    @Test
    void shouldPauseWorkflowWhenSoloAgentExecutionIsPaused() {
        WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
        WorkflowNodeExecutionMapper nodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        WorkflowNodeEngine engine = new WorkflowNodeEngine(
                instanceMapper,
                nodeExecutionMapper,
                mock(AgentExecutionMapper.class),
                mock(AgentMapper.class),
                mock(SpecMapper.class),
                mock(ObjectProvider.class),
                mock(AgentContextPublisher.class),
                mock(RabbitTemplate.class),
                mock(DeviationAnalysisHandler.class),
                mock(QualityReportHandler.class),
                mock(WorkflowNodeContextAssembler.class),
                mock(WorkflowArtifactService.class),
                mock(BuiltinQualityAssuranceService.class),
                mock(ObjectProvider.class)
        );

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-1");
        instance.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());
        when(instanceMapper.selectById("wf-1")).thenReturn(instance);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setInstanceId("wf-1");
        nodeExecution.setNodeId("node-agent");
        nodeExecution.setNodeLabel("实现接口");
        when(nodeExecutionMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of(nodeExecution));

        engine.onAgentNodeCompleted("wf-1", "node-agent", AgentExecutionStatusEnum.PAUSED.getCode(), "等待人工确认后继续");

        ArgumentCaptor<WorkflowNodeExecution> nodeCaptor = ArgumentCaptor.forClass(WorkflowNodeExecution.class);
        verify(nodeExecutionMapper).updateById(nodeCaptor.capture());
        assertThat(nodeCaptor.getValue().getStatus()).isEqualTo(WorkflowInstanceStatusEnum.PAUSED.getCode());
        assertThat(nodeCaptor.getValue().getErrorMessage()).contains("等待人工确认");

        ArgumentCaptor<WorkflowInstance> instanceCaptor = ArgumentCaptor.forClass(WorkflowInstance.class);
        verify(instanceMapper).updateById(instanceCaptor.capture());
        assertThat(instanceCaptor.getValue().getStatus()).isEqualTo(WorkflowInstanceStatusEnum.PAUSED.getCode());
    }

    @Test
    void shouldResumePausedWorkflowBeforeAdvancingAfterAgentRecovery() {
        WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
        WorkflowNodeExecutionMapper nodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        WorkflowArtifactService workflowArtifactService = mock(WorkflowArtifactService.class);
        BuiltinQualityAssuranceService builtinQualityAssuranceService = mock(BuiltinQualityAssuranceService.class);
        when(workflowArtifactService.persistAgentArtifactIfNecessary(any(), any(), eq("已恢复完成"))).thenReturn(java.util.Map.of());
        when(builtinQualityAssuranceService.analyzeAgentNode(any(), any(), eq("已恢复完成"))).thenReturn(java.util.Map.of());

        WorkflowNodeEngine engine = spy(new WorkflowNodeEngine(
                instanceMapper,
                nodeExecutionMapper,
                mock(AgentExecutionMapper.class),
                mock(AgentMapper.class),
                mock(SpecMapper.class),
                mock(ObjectProvider.class),
                mock(AgentContextPublisher.class),
                mock(RabbitTemplate.class),
                mock(DeviationAnalysisHandler.class),
                mock(QualityReportHandler.class),
                mock(WorkflowNodeContextAssembler.class),
                workflowArtifactService,
                builtinQualityAssuranceService,
                mock(ObjectProvider.class)
        ));
        doNothing().when(engine).advanceWorkflow(eq("wf-1"), eq("node-agent"), anyMap());

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-1");
        instance.setStatus(WorkflowInstanceStatusEnum.PAUSED.getCode());
        when(instanceMapper.selectById("wf-1")).thenReturn(instance);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setInstanceId("wf-1");
        nodeExecution.setNodeId("node-agent");
        nodeExecution.setNodeLabel("实现接口");
        when(nodeExecutionMapper.selectList(any())).thenReturn(java.util.List.of(nodeExecution));

        engine.onAgentNodeCompleted("wf-1", "node-agent", AgentExecutionStatusEnum.COMPLETED.getCode(), "已恢复完成");

        ArgumentCaptor<WorkflowInstance> instanceCaptor = ArgumentCaptor.forClass(WorkflowInstance.class);
        verify(instanceMapper).updateById(instanceCaptor.capture());
        assertThat(instanceCaptor.getValue().getStatus()).isEqualTo(WorkflowInstanceStatusEnum.RUNNING.getCode());
        verify(engine).advanceWorkflow(eq("wf-1"), eq("node-agent"), anyMap());
    }
}
