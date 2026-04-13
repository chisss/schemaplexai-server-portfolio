package com.schemaplexai.service.workflow.engine;

import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.RoleMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.UserRoleMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.runtime.AgentRuntimeOrchestrator;
import com.schemaplexai.service.mq.AgentContextPublisher;
import com.schemaplexai.service.notification.InAppMessageService;
import com.schemaplexai.service.quality.runtime.BuiltinQualityAssuranceService;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import com.schemaplexai.service.workflow.ReviewSessionService;
import com.schemaplexai.service.workflow.engine.assembler.WorkflowNodeContextAssembler;
import com.schemaplexai.service.workflow.engine.handler.DeviationAnalysisHandler;
import com.schemaplexai.service.workflow.engine.handler.QualityReportHandler;
import com.schemaplexai.service.workflow.runtime.WorkflowNotificationService.ResolvedNotificationMessage;
import com.schemaplexai.service.workflow.runtime.WorkflowArtifactService;
import com.schemaplexai.service.workflow.runtime.WorkflowNotificationService;
import com.schemaplexai.service.integration.notification.model.NotificationMessage;
import com.schemaplexai.model.vo.workflow.ReviewSessionVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowNodeEngineTest {

    @Test
    void shouldMapFailedWorkflowStatusToFailedSpecStatus() throws Exception {
        WorkflowNodeEngine engine = new WorkflowNodeEngine(
                mock(WorkflowInstanceMapper.class),
                mock(WorkflowNodeExecutionMapper.class),
                mock(AgentExecutionMapper.class),
                mock(AgentMapper.class),
                mock(SpecMapper.class),
                mock(RoleMapper.class),
                mock(UserRoleMapper.class),
                mock(ObjectProvider.class),
                mock(AgentContextPublisher.class),
                mock(RabbitTemplate.class),
                mock(DeviationAnalysisHandler.class),
                mock(QualityReportHandler.class),
                mock(WorkflowNodeContextAssembler.class),
                mock(WorkflowArtifactService.class),
                mock(WorkflowNotificationService.class),
                mock(ObjectProvider.class),
                mock(InAppMessageService.class),
                mock(BuiltinQualityAssuranceService.class),
                mock(ObjectProvider.class)
        );

        Method method = WorkflowNodeEngine.class.getDeclaredMethod(
                "resolveWorkflowDrivenSpecStatus", String.class, String.class, String.class);
        method.setAccessible(true);
        String status = (String) method.invoke(
                engine, WorkflowInstanceStatusEnum.FAILED.getCode(), "agent", "task_breakdown");

        assertThat(status).isEqualTo("failed");
    }

    @Test
    void shouldMapCompletedWorkflowStatusToCompletedSpecStatus() throws Exception {
        WorkflowNodeEngine engine = new WorkflowNodeEngine(
                mock(WorkflowInstanceMapper.class),
                mock(WorkflowNodeExecutionMapper.class),
                mock(AgentExecutionMapper.class),
                mock(AgentMapper.class),
                mock(SpecMapper.class),
                mock(RoleMapper.class),
                mock(UserRoleMapper.class),
                mock(ObjectProvider.class),
                mock(AgentContextPublisher.class),
                mock(RabbitTemplate.class),
                mock(DeviationAnalysisHandler.class),
                mock(QualityReportHandler.class),
                mock(WorkflowNodeContextAssembler.class),
                mock(WorkflowArtifactService.class),
                mock(WorkflowNotificationService.class),
                mock(ObjectProvider.class),
                mock(InAppMessageService.class),
                mock(BuiltinQualityAssuranceService.class),
                mock(ObjectProvider.class)
        );

        Method method = WorkflowNodeEngine.class.getDeclaredMethod(
                "resolveWorkflowDrivenSpecStatus", String.class, String.class, String.class);
        method.setAccessible(true);
        String status = (String) method.invoke(
                engine, WorkflowInstanceStatusEnum.COMPLETED.getCode(), "end", "end");

        assertThat(status).isEqualTo("completed");
    }

    @Test
    void shouldProvisionEnoughChatMemoryForWorkflowAgentToolRounds() {
        WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
        WorkflowNodeExecutionMapper nodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        AgentExecutionMapper agentExecutionMapper = mock(AgentExecutionMapper.class);
        AgentMapper agentMapper = mock(AgentMapper.class);
        WorkflowNodeContextAssembler contextAssembler = mock(WorkflowNodeContextAssembler.class);
        AgentRuntimeOrchestrator agentRuntimeOrchestrator = mock(AgentRuntimeOrchestrator.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentRuntimeOrchestrator> orchestratorProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SecurityRuntimeGuardService> securityProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ReviewSessionService> reviewSessionProvider = mock(ObjectProvider.class);
        SecurityRuntimeGuardService securityRuntimeGuardService = mock(SecurityRuntimeGuardService.class);

        when(orchestratorProvider.getObject()).thenReturn(agentRuntimeOrchestrator);
        when(securityProvider.getObject()).thenReturn(securityRuntimeGuardService);
        when(securityRuntimeGuardService.evaluate(any(), any())).thenReturn(null);
        when(contextAssembler.buildInputData(any(), anyMap())).thenReturn(new HashMap<>(Map.of("upstream", "分析当前需求")));
        when(contextAssembler.buildAgentExecutionPrompt(any(), anyString(), anyMap(), anyMap())).thenReturn("请完成需求分析");
        when(agentRuntimeOrchestrator.execute(any(AgentExecutionContext.class))).thenReturn(new CompletableFuture<>());
        when(agentExecutionMapper.insert(any(AgentExecution.class))).thenAnswer(invocation -> {
            AgentExecution execution = invocation.getArgument(0);
            execution.setId("exec-1");
            return 1;
        });

        Agent agent = new Agent();
        agent.setId("agent-1");
        agent.setAiModel("route-1");
        when(agentMapper.selectById("agent-1")).thenReturn(agent);

        WorkflowNodeEngine engine = new WorkflowNodeEngine(
                instanceMapper,
                nodeExecutionMapper,
                agentExecutionMapper,
                agentMapper,
                mock(SpecMapper.class),
                mock(RoleMapper.class),
                mock(UserRoleMapper.class),
                orchestratorProvider,
                mock(AgentContextPublisher.class),
                mock(RabbitTemplate.class),
                mock(DeviationAnalysisHandler.class),
                mock(QualityReportHandler.class),
                contextAssembler,
                mock(WorkflowArtifactService.class),
                mock(WorkflowNotificationService.class),
                reviewSessionProvider,
                mock(InAppMessageService.class),
                mock(BuiltinQualityAssuranceService.class),
                securityProvider
        );

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-1");
        instance.setTenantId("tenant-1");
        instance.setSpecId("spec-1");
        instance.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setId("node-exec-1");
        nodeExecution.setInstanceId("wf-1");
        nodeExecution.setNodeId("req_analysis");
        nodeExecution.setNodeLabel("需求分析");
        when(nodeExecutionMapper.selectList(any())).thenReturn(List.of(nodeExecution));

        List<Map<String, Object>> nodes = List.of(Map.of(
                "id", "req_analysis",
                "type", "agent",
                "label", "需求分析",
                "config", Map.of("agentId", "agent-1")
        ));

        engine.driveNode(instance, "req_analysis", nodes, List.of(), Map.of("upstream", "分析当前需求"));

        ArgumentCaptor<AgentExecutionContext> contextCaptor = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(agentRuntimeOrchestrator).execute(contextCaptor.capture());
        AgentExecutionContext context = contextCaptor.getValue();

        assertThat(context.getMaxRounds()).isEqualTo(10);
        assertThat(context.getMaxToolCallsPerRound()).isEqualTo(8);
        assertThat(context.getMaxMessages())
                .isEqualTo(WorkflowNodeEngine.resolveWorkflowAgentMaxMessages(
                        context.getMaxRounds(),
                        context.getMaxToolCallsPerRound()
                ))
                .isGreaterThan(96);
    }

    @Test
    void shouldFailWorkflowWhenAgentNodeHasNoBoundAgent() {
        WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
        WorkflowNodeExecutionMapper nodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        AgentExecutionMapper agentExecutionMapper = mock(AgentExecutionMapper.class);

        WorkflowNodeEngine engine = new WorkflowNodeEngine(
                instanceMapper,
                nodeExecutionMapper,
                agentExecutionMapper,
                mock(AgentMapper.class),
                mock(SpecMapper.class),
                mock(RoleMapper.class),
                mock(UserRoleMapper.class),
                mock(ObjectProvider.class),
                mock(AgentContextPublisher.class),
                mock(RabbitTemplate.class),
                mock(DeviationAnalysisHandler.class),
                mock(QualityReportHandler.class),
                mock(WorkflowNodeContextAssembler.class),
                mock(WorkflowArtifactService.class),
                mock(WorkflowNotificationService.class),
                mock(ObjectProvider.class),
                mock(InAppMessageService.class),
                mock(BuiltinQualityAssuranceService.class),
                mock(ObjectProvider.class)
        );

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-1");
        instance.setTenantId("tenant-1");
        instance.setSpecId("spec-1");
        instance.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());
        when(instanceMapper.selectById("wf-1")).thenReturn(instance);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setId("node-exec-1");
        nodeExecution.setInstanceId("wf-1");
        nodeExecution.setNodeId("copy_generation");
        nodeExecution.setNodeType("agent");
        nodeExecution.setNodeLabel("营销文案生成");
        when(nodeExecutionMapper.selectList(any())).thenReturn(List.of(nodeExecution));

        List<Map<String, Object>> nodes = List.of(Map.of(
                "id", "copy_generation",
                "type", "agent",
                "label", "营销文案生成",
                "config", Map.of()
        ));

        engine.driveNode(instance, "copy_generation", nodes, List.of(), Map.of());

        ArgumentCaptor<WorkflowNodeExecution> nodeCaptor = ArgumentCaptor.forClass(WorkflowNodeExecution.class);
        verify(nodeExecutionMapper, atLeastOnce()).updateById(nodeCaptor.capture());
        WorkflowNodeExecution finalNodeState = nodeCaptor.getAllValues().getLast();
        assertThat(finalNodeState.getStatus()).isEqualTo(WorkflowInstanceStatusEnum.FAILED.getCode());
        assertThat(finalNodeState.getErrorMessage()).contains("Agent节点未绑定Agent");
        verify(agentExecutionMapper, never()).insert(any(AgentExecution.class));
    }

    @Test
    void shouldAllowWorkflowNodeToOverrideAgentExecutionBudget() {
        WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
        WorkflowNodeExecutionMapper nodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        AgentExecutionMapper agentExecutionMapper = mock(AgentExecutionMapper.class);
        AgentMapper agentMapper = mock(AgentMapper.class);
        WorkflowNodeContextAssembler contextAssembler = mock(WorkflowNodeContextAssembler.class);
        AgentRuntimeOrchestrator agentRuntimeOrchestrator = mock(AgentRuntimeOrchestrator.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentRuntimeOrchestrator> orchestratorProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SecurityRuntimeGuardService> securityProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ReviewSessionService> reviewSessionProvider = mock(ObjectProvider.class);
        SecurityRuntimeGuardService securityRuntimeGuardService = mock(SecurityRuntimeGuardService.class);

        when(orchestratorProvider.getObject()).thenReturn(agentRuntimeOrchestrator);
        when(securityProvider.getObject()).thenReturn(securityRuntimeGuardService);
        when(securityRuntimeGuardService.evaluate(any(), any())).thenReturn(null);
        when(contextAssembler.buildInputData(any(), anyMap())).thenReturn(new HashMap<>(Map.of("upstream", "分析当前需求")));
        when(contextAssembler.buildAgentExecutionPrompt(any(), anyString(), anyMap(), anyMap())).thenReturn("请完成需求分析");
        when(agentRuntimeOrchestrator.execute(any(AgentExecutionContext.class))).thenReturn(new CompletableFuture<>());
        when(agentExecutionMapper.insert(any(AgentExecution.class))).thenAnswer(invocation -> {
            AgentExecution execution = invocation.getArgument(0);
            execution.setId("exec-2");
            return 1;
        });

        Agent agent = new Agent();
        agent.setId("agent-2");
        agent.setAiModel("route-2");
        when(agentMapper.selectById("agent-2")).thenReturn(agent);

        WorkflowNodeEngine engine = new WorkflowNodeEngine(
                instanceMapper,
                nodeExecutionMapper,
                agentExecutionMapper,
                agentMapper,
                mock(SpecMapper.class),
                mock(RoleMapper.class),
                mock(UserRoleMapper.class),
                orchestratorProvider,
                mock(AgentContextPublisher.class),
                mock(RabbitTemplate.class),
                mock(DeviationAnalysisHandler.class),
                mock(QualityReportHandler.class),
                contextAssembler,
                mock(WorkflowArtifactService.class),
                mock(WorkflowNotificationService.class),
                reviewSessionProvider,
                mock(InAppMessageService.class),
                mock(BuiltinQualityAssuranceService.class),
                securityProvider
        );

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-2");
        instance.setTenantId("tenant-2");
        instance.setSpecId("spec-2");
        instance.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setId("node-exec-2");
        nodeExecution.setInstanceId("wf-2");
        nodeExecution.setNodeId("req_analysis");
        nodeExecution.setNodeLabel("需求分析");
        when(nodeExecutionMapper.selectList(any())).thenReturn(List.of(nodeExecution));

        List<Map<String, Object>> nodes = List.of(Map.of(
                "id", "req_analysis",
                "type", "agent",
                "label", "需求分析",
                "config", Map.of(
                        "agentId", "agent-2",
                        "maxRounds", 12,
                        "maxToolCallsPerRound", 6
                )
        ));

        engine.driveNode(instance, "req_analysis", nodes, List.of(), Map.of("upstream", "分析当前需求"));

        ArgumentCaptor<AgentExecutionContext> contextCaptor = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(agentRuntimeOrchestrator).execute(contextCaptor.capture());
        AgentExecutionContext context = contextCaptor.getValue();

        assertThat(context.getMaxRounds()).isEqualTo(12);
        assertThat(context.getMaxToolCallsPerRound()).isEqualTo(6);
    }

    @Test
    void shouldClearNullableScalarColumnsWithoutWritingJsonbViaWrapperWhenDrivingNode() {
        WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
        WorkflowNodeExecutionMapper nodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        AgentExecutionMapper agentExecutionMapper = mock(AgentExecutionMapper.class);
        AgentMapper agentMapper = mock(AgentMapper.class);
        WorkflowNodeContextAssembler contextAssembler = mock(WorkflowNodeContextAssembler.class);
        AgentRuntimeOrchestrator agentRuntimeOrchestrator = mock(AgentRuntimeOrchestrator.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentRuntimeOrchestrator> orchestratorProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SecurityRuntimeGuardService> securityProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ReviewSessionService> reviewSessionProvider = mock(ObjectProvider.class);
        SecurityRuntimeGuardService securityRuntimeGuardService = mock(SecurityRuntimeGuardService.class);

        when(orchestratorProvider.getObject()).thenReturn(agentRuntimeOrchestrator);
        when(securityProvider.getObject()).thenReturn(securityRuntimeGuardService);
        when(securityRuntimeGuardService.evaluate(any(), any())).thenReturn(null);
        when(contextAssembler.buildInputData(any(), anyMap())).thenReturn(new HashMap<>(Map.of("origin", "spec")));
        when(contextAssembler.buildAgentExecutionPrompt(any(), anyString(), anyMap(), anyMap())).thenReturn("请生成需求分析");
        when(agentRuntimeOrchestrator.execute(any(AgentExecutionContext.class))).thenReturn(new CompletableFuture<>());
        when(agentExecutionMapper.insert(any(AgentExecution.class))).thenAnswer(invocation -> {
            AgentExecution execution = invocation.getArgument(0);
            execution.setId("exec-json-1");
            return 1;
        });

        Agent agent = new Agent();
        agent.setId("agent-json-1");
        agent.setAiModel("route-json-1");
        when(agentMapper.selectById("agent-json-1")).thenReturn(agent);

        WorkflowNodeEngine engine = new WorkflowNodeEngine(
                instanceMapper,
                nodeExecutionMapper,
                agentExecutionMapper,
                agentMapper,
                mock(SpecMapper.class),
                mock(RoleMapper.class),
                mock(UserRoleMapper.class),
                orchestratorProvider,
                mock(AgentContextPublisher.class),
                mock(RabbitTemplate.class),
                mock(DeviationAnalysisHandler.class),
                mock(QualityReportHandler.class),
                contextAssembler,
                mock(WorkflowArtifactService.class),
                mock(WorkflowNotificationService.class),
                reviewSessionProvider,
                mock(InAppMessageService.class),
                mock(BuiltinQualityAssuranceService.class),
                securityProvider
        );

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-json-1");
        instance.setTenantId("tenant-json-1");
        instance.setSpecId("spec-json-1");
        instance.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setId("node-json-1");
        nodeExecution.setInstanceId("wf-json-1");
        nodeExecution.setNodeId("requirements_analysis");
        nodeExecution.setNodeLabel("需求分析");
        when(nodeExecutionMapper.selectList(any())).thenReturn(List.of(nodeExecution));

        List<Map<String, Object>> nodes = List.of(Map.of(
                "id", "requirements_analysis",
                "type", "agent",
                "label", "需求分析",
                "config", Map.of("agentId", "agent-json-1")
        ));

        engine.driveNode(instance, "requirements_analysis", nodes, List.of(), Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<WorkflowNodeExecution>> clearCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(nodeExecutionMapper).update(eq(null), clearCaptor.capture());
        assertThat(clearCaptor.getValue().getSqlSet())
                .contains("completed_at")
                .contains("error_message")
                .doesNotContain("input_data")
                .doesNotContain("output_data");
        verify(nodeExecutionMapper, atLeastOnce()).updateById(any(WorkflowNodeExecution.class));
    }

    @Test
    void shouldPauseWorkflowWhenSoloAgentExecutionIsPaused() {
        WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
        WorkflowNodeExecutionMapper nodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ReviewSessionService> reviewSessionProvider = mock(ObjectProvider.class);
        WorkflowNodeEngine engine = new WorkflowNodeEngine(
                instanceMapper,
                nodeExecutionMapper,
                mock(AgentExecutionMapper.class),
                mock(AgentMapper.class),
                mock(SpecMapper.class),
                mock(RoleMapper.class),
                mock(UserRoleMapper.class),
                mock(ObjectProvider.class),
                mock(AgentContextPublisher.class),
                mock(RabbitTemplate.class),
                mock(DeviationAnalysisHandler.class),
                mock(QualityReportHandler.class),
                mock(WorkflowNodeContextAssembler.class),
                mock(WorkflowArtifactService.class),
                mock(WorkflowNotificationService.class),
                reviewSessionProvider,
                mock(InAppMessageService.class),
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
        when(workflowArtifactService.persistAgentArtifactIfNecessary(any(), any(), any(), eq("已恢复完成"), anyMap()))
                .thenReturn(java.util.Map.of());
        when(builtinQualityAssuranceService.analyzeAgentNode(any(), any(), eq("已恢复完成"))).thenReturn(java.util.Map.of());
        @SuppressWarnings("unchecked")
        ObjectProvider<ReviewSessionService> reviewSessionProvider = mock(ObjectProvider.class);

        WorkflowNodeEngine engine = spy(new WorkflowNodeEngine(
                instanceMapper,
                nodeExecutionMapper,
                mock(AgentExecutionMapper.class),
                mock(AgentMapper.class),
                mock(SpecMapper.class),
                mock(RoleMapper.class),
                mock(UserRoleMapper.class),
                mock(ObjectProvider.class),
                mock(AgentContextPublisher.class),
                mock(RabbitTemplate.class),
                mock(DeviationAnalysisHandler.class),
                mock(QualityReportHandler.class),
                mock(WorkflowNodeContextAssembler.class),
                workflowArtifactService,
                mock(WorkflowNotificationService.class),
                reviewSessionProvider,
                mock(InAppMessageService.class),
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

    @Test
    void shouldSkipAgentNodeFailureWhenFailureStrategyIsSkip() {
        WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
        WorkflowNodeExecutionMapper nodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ReviewSessionService> reviewSessionProvider = mock(ObjectProvider.class);

        WorkflowNodeEngine engine = spy(new WorkflowNodeEngine(
                instanceMapper,
                nodeExecutionMapper,
                mock(AgentExecutionMapper.class),
                mock(AgentMapper.class),
                mock(SpecMapper.class),
                mock(RoleMapper.class),
                mock(UserRoleMapper.class),
                mock(ObjectProvider.class),
                mock(AgentContextPublisher.class),
                mock(RabbitTemplate.class),
                mock(DeviationAnalysisHandler.class),
                mock(QualityReportHandler.class),
                mock(WorkflowNodeContextAssembler.class),
                mock(WorkflowArtifactService.class),
                mock(WorkflowNotificationService.class),
                reviewSessionProvider,
                mock(InAppMessageService.class),
                mock(BuiltinQualityAssuranceService.class),
                mock(ObjectProvider.class)
        ));
        doNothing().when(engine).advanceWorkflow(eq("wf-skip"), eq("doc_generation"), anyMap());

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-skip");
        instance.setTenantId("tenant-1");
        instance.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());
        instance.setDefinition(Map.of(
                "nodes", List.of(Map.of(
                        "id", "doc_generation",
                        "type", "agent",
                        "label", "文档生成",
                        "config", Map.of("failureStrategy", "skip")
                )),
                "edges", List.of()
        ));
        when(instanceMapper.selectById("wf-skip")).thenReturn(instance);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setId("node-exec-skip");
        nodeExecution.setInstanceId("wf-skip");
        nodeExecution.setNodeId("doc_generation");
        nodeExecution.setNodeType("agent");
        nodeExecution.setNodeLabel("文档生成");
        when(nodeExecutionMapper.selectList(any())).thenReturn(List.of(nodeExecution));

        engine.onAgentNodeCompleted(
                "wf-skip",
                "doc_generation",
                AgentExecutionStatusEnum.FAILED.getCode(),
                "{\"error\":{\"code\":\"1302\",\"message\":\"您的账户已达到速率限制，请您控制请求频率\"}}"
        );

        ArgumentCaptor<WorkflowNodeExecution> nodeCaptor = ArgumentCaptor.forClass(WorkflowNodeExecution.class);
        verify(nodeExecutionMapper).updateById(nodeCaptor.capture());
        WorkflowNodeExecution finalNodeState = nodeCaptor.getValue();
        assertThat(finalNodeState.getStatus()).isEqualTo(WorkflowInstanceStatusEnum.COMPLETED.getCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> tracePayload = (Map<String, Object>) finalNodeState.getOutputData().get("tracePayload");
        assertThat(tracePayload.get("skipped")).isEqualTo(true);
        assertThat(tracePayload.get("failureStrategy")).isEqualTo("skip");
        assertThat(String.valueOf(tracePayload.get("skipReason"))).contains("1302");

        ArgumentCaptor<Map<String, Object>> outputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(engine).advanceWorkflow(eq("wf-skip"), eq("doc_generation"), outputCaptor.capture());
        assertThat(outputCaptor.getValue()).containsEntry("skipped", true);
    }

    @Test
    void shouldFailWorkflowWhenFailureStrategyIsTerminate() {
        WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
        WorkflowNodeExecutionMapper nodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ReviewSessionService> reviewSessionProvider = mock(ObjectProvider.class);

        WorkflowNodeEngine engine = new WorkflowNodeEngine(
                instanceMapper,
                nodeExecutionMapper,
                mock(AgentExecutionMapper.class),
                mock(AgentMapper.class),
                mock(SpecMapper.class),
                mock(RoleMapper.class),
                mock(UserRoleMapper.class),
                mock(ObjectProvider.class),
                mock(AgentContextPublisher.class),
                mock(RabbitTemplate.class),
                mock(DeviationAnalysisHandler.class),
                mock(QualityReportHandler.class),
                mock(WorkflowNodeContextAssembler.class),
                mock(WorkflowArtifactService.class),
                mock(WorkflowNotificationService.class),
                reviewSessionProvider,
                mock(InAppMessageService.class),
                mock(BuiltinQualityAssuranceService.class),
                mock(ObjectProvider.class)
        );

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-fail");
        instance.setTenantId("tenant-1");
        instance.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());
        instance.setDefinition(Map.of(
                "nodes", List.of(Map.of(
                        "id", "doc_generation",
                        "type", "agent",
                        "label", "文档生成",
                        "config", Map.of("failureStrategy", "terminate")
                )),
                "edges", List.of()
        ));
        when(instanceMapper.selectById("wf-fail")).thenReturn(instance);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setId("node-exec-fail");
        nodeExecution.setInstanceId("wf-fail");
        nodeExecution.setNodeId("doc_generation");
        nodeExecution.setNodeType("agent");
        nodeExecution.setNodeLabel("文档生成");
        when(nodeExecutionMapper.selectList(any())).thenReturn(List.of(nodeExecution));

        engine.onAgentNodeCompleted(
                "wf-fail",
                "doc_generation",
                AgentExecutionStatusEnum.FAILED.getCode(),
                "模型调用失败"
        );

        ArgumentCaptor<WorkflowNodeExecution> nodeCaptor = ArgumentCaptor.forClass(WorkflowNodeExecution.class);
        verify(nodeExecutionMapper).updateById(nodeCaptor.capture());
        assertThat(nodeCaptor.getValue().getStatus()).isEqualTo(WorkflowInstanceStatusEnum.FAILED.getCode());

        ArgumentCaptor<WorkflowInstance> instanceCaptor = ArgumentCaptor.forClass(WorkflowInstance.class);
        verify(instanceMapper).updateById(instanceCaptor.capture());
        assertThat(instanceCaptor.getValue().getStatus()).isEqualTo(WorkflowInstanceStatusEnum.FAILED.getCode());
    }

    @Test
    void shouldPauseHumanReviewWithoutOverwritingCurrentNodeAndUseRelativeActionUrl() {
        WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
        WorkflowNodeExecutionMapper nodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        WorkflowNotificationService workflowNotificationService = mock(WorkflowNotificationService.class);
        InAppMessageService inAppMessageService = mock(InAppMessageService.class);
        ReviewSessionService reviewSessionService = mock(ReviewSessionService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ReviewSessionService> reviewSessionProvider = mock(ObjectProvider.class);
        when(reviewSessionProvider.getObject()).thenReturn(reviewSessionService);

        ReviewSessionVO reviewSession = new ReviewSessionVO();
        reviewSession.setId("session-1");
        reviewSession.setReviewActionUrl("/spec/spec-1/edit?instanceId=wf-1&nodeId=requirements_review&mode=review");
        when(reviewSessionService.create(any())).thenReturn(reviewSession);
        when(workflowNotificationService.buildSpecReviewActionPath("spec-1", "wf-1", "requirements_review", null))
                .thenReturn("/spec/spec-1/edit?instanceId=wf-1&nodeId=requirements_review&mode=review");
        when(workflowNotificationService.buildSpecReviewActionUrl("spec-1", "wf-1", "requirements_review", null))
                .thenReturn("https://frontend.example/spec/spec-1/edit?instanceId=wf-1&nodeId=requirements_review&mode=review");
        when(workflowNotificationService.resolveHumanReviewMessage(any(), any(), anyMap(), anyString()))
                .thenReturn(new ResolvedNotificationMessage(
                        NotificationMessage.builder()
                                .title("待审核")
                                .content("请完成审核")
                                .previewUrl("https://frontend.example/spec/spec-1/edit?instanceId=wf-1&nodeId=requirements_review&mode=review")
                                .build(),
                        null,
                        null,
                        null
                ));
        when(workflowNotificationService.sendHumanReviewNotifications(any(), any(), anyMap(), anyString()))
                .thenReturn(List.of());

        WorkflowNodeEngine engine = new WorkflowNodeEngine(
                instanceMapper,
                nodeExecutionMapper,
                mock(AgentExecutionMapper.class),
                mock(AgentMapper.class),
                mock(SpecMapper.class),
                mock(RoleMapper.class),
                mock(UserRoleMapper.class),
                mock(ObjectProvider.class),
                mock(AgentContextPublisher.class),
                mock(RabbitTemplate.class),
                mock(DeviationAnalysisHandler.class),
                mock(QualityReportHandler.class),
                mock(WorkflowNodeContextAssembler.class),
                mock(WorkflowArtifactService.class),
                workflowNotificationService,
                reviewSessionProvider,
                inAppMessageService,
                mock(BuiltinQualityAssuranceService.class),
                mock(ObjectProvider.class)
        );

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-1");
        instance.setSpecId("spec-1");
        instance.setTenantId("tenant-1");
        instance.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());
        instance.setCurrentNodeId("requirements_doc");

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setId("node-exec-1");
        nodeExecution.setInstanceId("wf-1");
        nodeExecution.setNodeId("requirements_review");
        nodeExecution.setNodeType("human_review");
        nodeExecution.setNodeLabel("需求文档审批");
        when(nodeExecutionMapper.selectList(any())).thenReturn(List.of(nodeExecution));

        List<Map<String, Object>> nodes = List.of(Map.of(
                "id", "requirements_review",
                "type", "human_review",
                "label", "需求文档审批",
                "config", Map.of(
                        "reviewerIds", List.of("reviewer-1"),
                        "documentKey", "requirements"
                )
        ));

        engine.driveNode(instance, "requirements_review", nodes, List.of(), Map.of());

        ArgumentCaptor<WorkflowInstance> instanceCaptor = ArgumentCaptor.forClass(WorkflowInstance.class);
        verify(instanceMapper).updateById(instanceCaptor.capture());
        WorkflowInstance pausedUpdate = instanceCaptor.getAllValues().getLast();
        assertThat(pausedUpdate.getId()).isEqualTo("wf-1");
        assertThat(pausedUpdate.getStatus()).isEqualTo(WorkflowInstanceStatusEnum.PAUSED.getCode());
        assertThat(pausedUpdate.getCurrentNodeId()).isNull();

        ArgumentCaptor<WorkflowNodeExecution> nodeCaptor = ArgumentCaptor.forClass(WorkflowNodeExecution.class);
        verify(nodeExecutionMapper, org.mockito.Mockito.atLeastOnce()).updateById(nodeCaptor.capture());
        WorkflowNodeExecution finalNodeState = nodeCaptor.getAllValues().getLast();
        assertThat(finalNodeState.getActionUrl())
                .isEqualTo("/spec/spec-1/edit?instanceId=wf-1&nodeId=requirements_review&mode=review");
        assertThat(finalNodeState.getReviewSessionId()).isEqualTo("session-1");

        verify(workflowNotificationService).sendHumanReviewNotifications(any(), any(), anyMap(),
                eq("https://frontend.example/spec/spec-1/edit?instanceId=wf-1&nodeId=requirements_review&mode=review"));
        verify(inAppMessageService).archivePendingReviewMessages(
                eq("tenant-1"),
                eq("spec-1"),
                eq("requirements_review"),
                eq("node-exec-1")
        );
        verify(inAppMessageService).createMessage(
                eq("tenant-1"),
                eq("待审核"),
                eq("请完成审核"),
                eq("todo"),
                eq("human_review_pending"),
                eq("workflow_human_review"),
                eq("node-exec-1"),
                eq("/spec/spec-1/edit?instanceId=wf-1&nodeId=requirements_review&mode=review"),
                anyMap(),
                eq(List.of("reviewer-1"))
        );
        var inOrderVerifier = inOrder(inAppMessageService);
        inOrderVerifier.verify(inAppMessageService)
                .archivePendingReviewMessages("tenant-1", "spec-1", "requirements_review", "node-exec-1");
        inOrderVerifier.verify(inAppMessageService)
                .createMessage(
                        eq("tenant-1"),
                        eq("待审核"),
                        eq("请完成审核"),
                        eq("todo"),
                        eq("human_review_pending"),
                        eq("workflow_human_review"),
                        eq("node-exec-1"),
                        eq("/spec/spec-1/edit?instanceId=wf-1&nodeId=requirements_review&mode=review"),
                        anyMap(),
                        eq(List.of("reviewer-1"))
                );
        verify(instanceMapper, never()).selectById(anyString());
    }

    @Test
    void shouldNotifySpecOwnerWhenQualityGatePausesWorkflow() {
        WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
        WorkflowNodeExecutionMapper nodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        AgentExecutionMapper agentExecutionMapper = mock(AgentExecutionMapper.class);
        SpecMapper specMapper = mock(SpecMapper.class);
        InAppMessageService inAppMessageService = mock(InAppMessageService.class);
        BuiltinQualityAssuranceService builtinQualityAssuranceService = mock(BuiltinQualityAssuranceService.class);
        WorkflowArtifactService workflowArtifactService = mock(WorkflowArtifactService.class);

        WorkflowNodeEngine engine = new WorkflowNodeEngine(
                instanceMapper,
                nodeExecutionMapper,
                agentExecutionMapper,
                mock(AgentMapper.class),
                specMapper,
                mock(RoleMapper.class),
                mock(UserRoleMapper.class),
                mock(ObjectProvider.class),
                mock(AgentContextPublisher.class),
                mock(RabbitTemplate.class),
                mock(DeviationAnalysisHandler.class),
                mock(QualityReportHandler.class),
                mock(WorkflowNodeContextAssembler.class),
                workflowArtifactService,
                mock(WorkflowNotificationService.class),
                mock(ObjectProvider.class),
                inAppMessageService,
                builtinQualityAssuranceService,
                mock(ObjectProvider.class)
        );

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-1");
        instance.setTenantId("tenant-1");
        instance.setSpecId("spec-1");
        instance.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());
        instance.setDefinition(Map.of(
                "nodes", List.of(Map.of(
                        "id", "copy_generation",
                        "type", "agent",
                        "label", "营销文案生成",
                        "config", Map.of("agentId", "agent-1")
                ))
        ));
        when(instanceMapper.selectById("wf-1")).thenReturn(instance);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setId("node-exec-1");
        nodeExecution.setInstanceId("wf-1");
        nodeExecution.setNodeId("copy_generation");
        nodeExecution.setNodeType("agent");
        nodeExecution.setNodeLabel("营销文案生成");
        when(nodeExecutionMapper.selectList(any())).thenReturn(List.of(nodeExecution));

        Spec spec = new Spec();
        spec.setId("spec-1");
        spec.setOwner("owner-1");
        when(specMapper.selectById("spec-1")).thenReturn(spec);
        when(builtinQualityAssuranceService.analyzeAgentNode(instance, nodeExecution, "最终交付文案"))
                .thenReturn(Map.of("criticalCount", 1, "qualityScore", 52));
        when(builtinQualityAssuranceService.evaluateWorkflowNodeGate(anyMap(), anyMap()))
                .thenReturn(BuiltinQualityAssuranceService.WorkflowNodeQualityGateDecision.pause("检测到阻断级偏离，需人工处理"));
        when(workflowArtifactService.persistAgentArtifactIfNecessary(any(), any(), anyMap(), anyString(), anyMap()))
                .thenReturn(Map.of());

        engine.onAgentNodeCompleted("wf-1", "copy_generation", AgentExecutionStatusEnum.COMPLETED.getCode(), "最终交付文案");

        verify(inAppMessageService).createMessage(
                eq("tenant-1"),
                eq("工作流已暂停，待处理质量阻断"),
                contains("营销文案生成"),
                eq("alert"),
                eq("workflow_quality_gate_paused"),
                eq("workflow_quality_gate"),
                eq("node-exec-1"),
                eq("/workflow/wf-1"),
                anyMap(),
                eq(List.of("owner-1"))
        );
    }

    @Test
    void shouldClearStaleExecutionMarkersWhenRerunningNode() {
        WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
        WorkflowNodeExecutionMapper nodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        AgentExecutionMapper agentExecutionMapper = mock(AgentExecutionMapper.class);
        AgentMapper agentMapper = mock(AgentMapper.class);
        WorkflowNodeContextAssembler contextAssembler = mock(WorkflowNodeContextAssembler.class);
        AgentRuntimeOrchestrator agentRuntimeOrchestrator = mock(AgentRuntimeOrchestrator.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentRuntimeOrchestrator> orchestratorProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SecurityRuntimeGuardService> securityProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ReviewSessionService> reviewSessionProvider = mock(ObjectProvider.class);
        SecurityRuntimeGuardService securityRuntimeGuardService = mock(SecurityRuntimeGuardService.class);

        when(orchestratorProvider.getObject()).thenReturn(agentRuntimeOrchestrator);
        when(securityProvider.getObject()).thenReturn(securityRuntimeGuardService);
        when(securityRuntimeGuardService.evaluate(any(), any())).thenReturn(null);
        when(contextAssembler.buildInputData(any(), anyMap())).thenReturn(new HashMap<>(Map.of("upstream", "任务拆分重跑")));
        when(contextAssembler.buildAgentExecutionPrompt(any(), anyString(), anyMap(), anyMap())).thenReturn("请重新执行任务拆分");
        when(agentRuntimeOrchestrator.execute(any(AgentExecutionContext.class))).thenReturn(new CompletableFuture<>());
        when(agentExecutionMapper.insert(any(AgentExecution.class))).thenAnswer(invocation -> {
            AgentExecution execution = invocation.getArgument(0);
            execution.setId("exec-rerun");
            return 1;
        });

        Agent agent = new Agent();
        agent.setId("agent-rerun");
        agent.setAiModel("route-rerun");
        when(agentMapper.selectById("agent-rerun")).thenReturn(agent);

        WorkflowNodeEngine engine = new WorkflowNodeEngine(
                instanceMapper,
                nodeExecutionMapper,
                agentExecutionMapper,
                agentMapper,
                mock(SpecMapper.class),
                mock(RoleMapper.class),
                mock(UserRoleMapper.class),
                orchestratorProvider,
                mock(AgentContextPublisher.class),
                mock(RabbitTemplate.class),
                mock(DeviationAnalysisHandler.class),
                mock(QualityReportHandler.class),
                contextAssembler,
                mock(WorkflowArtifactService.class),
                mock(WorkflowNotificationService.class),
                reviewSessionProvider,
                mock(InAppMessageService.class),
                mock(BuiltinQualityAssuranceService.class),
                securityProvider
        );

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-rerun");
        instance.setTenantId("tenant-1");
        instance.setSpecId("spec-1");
        instance.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setId("node-exec-rerun");
        nodeExecution.setInstanceId("wf-rerun");
        nodeExecution.setNodeId("task_breakdown");
        nodeExecution.setNodeLabel("任务拆分");
        nodeExecution.setStatus(WorkflowInstanceStatusEnum.COMPLETED.getCode());
        nodeExecution.setCompletedAt(LocalDateTime.now().minusMinutes(5));
        nodeExecution.setErrorMessage("上一轮执行失败后遗留的错误");
        nodeExecution.setOutputData(new HashMap<>(Map.of("result", "旧产物")));
        when(nodeExecutionMapper.selectList(any())).thenReturn(List.of(nodeExecution));

        List<Map<String, Object>> nodes = List.of(Map.of(
                "id", "task_breakdown",
                "type", "agent",
                "label", "任务拆分",
                "config", Map.of("agentId", "agent-rerun")
        ));

        engine.driveNode(instance, "task_breakdown", nodes, List.of(), Map.of("reviewComment", "补充回归测试"));

        ArgumentCaptor<WorkflowNodeExecution> nodeCaptor = ArgumentCaptor.forClass(WorkflowNodeExecution.class);
        verify(nodeExecutionMapper, org.mockito.Mockito.atLeastOnce()).updateById(nodeCaptor.capture());
        WorkflowNodeExecution rerunStartState = nodeCaptor.getAllValues().get(0);

        assertThat(rerunStartState.getStatus()).isEqualTo(WorkflowInstanceStatusEnum.RUNNING.getCode());
        assertThat(rerunStartState.getCompletedAt()).isNull();
        assertThat(rerunStartState.getErrorMessage()).isNull();
    }
}
