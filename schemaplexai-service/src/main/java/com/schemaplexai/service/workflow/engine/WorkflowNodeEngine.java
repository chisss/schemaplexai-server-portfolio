package com.schemaplexai.service.workflow.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.AgentRuntimeEngineEnum;
import com.schemaplexai.common.enums.NodeTypeEnum;
import com.schemaplexai.common.enums.SpecLifecycleModeEnum;
import com.schemaplexai.common.enums.SpecStatusEnum;
import com.schemaplexai.common.enums.ExecutionStrategyEnum;
import com.schemaplexai.common.enums.QualityIssueTypeEnum;
import com.schemaplexai.common.enums.TaskStatusEnum;
import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.RoleMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.UserRoleMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.QualityIssue;
import com.schemaplexai.model.entity.Role;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.UserRole;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest;
import com.schemaplexai.model.dto.workflow.ReviewSessionCreateRequest;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.model.vo.workflow.ReviewSessionVO;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;
import com.schemaplexai.service.agent.runtime.AgentRuntimeOrchestrator;
import com.schemaplexai.service.mq.AgentContextPublisher;
import com.schemaplexai.service.quality.gate.QualityGateDecision;
import com.schemaplexai.service.quality.runtime.BuiltinQualityAssuranceService;
import com.schemaplexai.service.notification.InAppMessageService;
import com.schemaplexai.service.quality.feedback.QualityIssueFeedbackService;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import com.schemaplexai.service.quality.pipeline.QualityCheckRequest;
import com.schemaplexai.service.quality.pipeline.QualityCheckResult;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy;
import com.schemaplexai.service.workflow.ReviewSessionService;
import com.schemaplexai.service.workflow.engine.assembler.WorkflowNodeContextAssembler;
import com.schemaplexai.service.workflow.engine.handler.DeviationAnalysisHandler;
import com.schemaplexai.service.workflow.engine.handler.QualityReportHandler;
import com.schemaplexai.service.workflow.flowable.FlowableWorkflowBridge;
import com.schemaplexai.service.workflow.runtime.WorkflowArtifactService;
import com.schemaplexai.service.workflow.runtime.WorkflowNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工作流节点驱动引擎
 *
 * <p>负责解析工作流定义（definition JSONB），按节点类型驱动各步骤：
 * <ul>
 *   <li>trigger/start → 自动完成，推进到下一节点</li>
 *   <li>agent → 触发 AgentRuntimeOrchestrator 异步执行，完成后继续推进</li>
 *   <li>human_review → 置为 pending，等待人工 approve/reject</li>
 *   <li>deviation_analysis → 委托 {@link DeviationAnalysisHandler} 执行</li>
 *   <li>quality_report → 委托 {@link QualityReportHandler} 生成</li>
 *   <li>end → 完成工作流实例</li>
 * </ul>
 *
 * <p>节点间上下文通过轻量 handoffPayload 传递，节点完整输入输出保留在 WorkflowNodeExecution.outputData 中。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowNodeEngine {

    private static final int DEFAULT_WORKFLOW_AGENT_MAX_ROUNDS = 10;
    private static final int DEFAULT_WORKFLOW_AGENT_MAX_TOOL_CALLS_PER_ROUND = 8;
    private static final int MAX_WORKFLOW_AGENT_MAX_ROUNDS = 16;
    private static final int MAX_WORKFLOW_AGENT_MAX_TOOL_CALLS_PER_ROUND = 12;
    private static final int WORKFLOW_AGENT_MIN_MAX_MESSAGES = 64;

    private final WorkflowInstanceMapper instanceMapper;
    private final WorkflowNodeExecutionMapper nodeExecutionMapper;
    private final AgentExecutionMapper agentExecutionMapper;
    private final AgentMapper agentMapper;
    private final SpecMapper specMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final ObjectProvider<AgentRuntimeOrchestrator> agentRuntimeOrchestratorProvider;
    private final AgentContextPublisher agentContextPublisher;
    private final RabbitTemplate rabbitTemplate;
    private final DeviationAnalysisHandler deviationAnalysisHandler;
    private final QualityReportHandler qualityReportHandler;
    private final WorkflowNodeContextAssembler contextAssembler;
    private final WorkflowArtifactService workflowArtifactService;
    private final WorkflowNotificationService workflowNotificationService;
    private final ObjectProvider<ReviewSessionService> reviewSessionServiceProvider;
    private final InAppMessageService inAppMessageService;
    private final BuiltinQualityAssuranceService builtinQualityAssuranceService;
    private final QualityIssueFeedbackService qualityIssueFeedbackService;
    private final ObjectProvider<SecurityRuntimeGuardService> securityRuntimeGuardServiceProvider;
    private final FlowableWorkflowBridge flowableBridge;

    /**
     * 自注入自身代理，用于让 @Async 注解在同类方法调用时生效（绕过 Spring AOP 自调用限制）
     */
    @Lazy
    @Autowired
    private WorkflowNodeEngine self;

    // =====================================================================
    //  公开入口
    // =====================================================================

    /**
     * 工作流启动时初始化所有节点执行记录，并驱动第一个节点
     */
    @Transactional(rollbackFor = Exception.class)
    public void initAndDriveWorkflow(WorkflowInstance instance) {
        Map<String, Object> definition = instance.getDefinition();
        if (definition == null) {
            log.warn("工作流定义为空，无法初始化: instanceId={}", instance.getId());
            return;
        }

        List<Map<String, Object>> nodes = getNodes(definition);
        List<Map<String, Object>> edges = getEdges(definition);

        if (nodes.isEmpty()) {
            log.warn("工作流节点为空: instanceId={}", instance.getId());
            completeWorkflowInstance(instance.getId(), WorkflowInstanceStatusEnum.COMPLETED.getCode(), null);
            return;
        }

        initNodeExecutions(instance, nodes);

        String startNodeId = findStartNodeId(nodes);
        if (!StringUtils.hasText(startNodeId)) {
            log.warn("未找到起始节点: instanceId={}", instance.getId());
            return;
        }

        Map<String, Object> startNodeDef = findNodeDef(startNodeId, nodes);
        updateCurrentNode(instance, startNodeId, str(startNodeDef, "type"), str(startNodeDef, "label"));
        driveNodeAfterCommit(instance, startNodeId, nodes, edges, new HashMap<>());
    }

    /**
     * 仅初始化节点执行记录（不驱动首节点），供 Flowable 模式使用。
     * <p>Flowable 启动流程后会自动驱动首节点，因此只需初始化 sf_workflow_node_execution 记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public void initNodeExecutionRecords(WorkflowInstance instance) {
        Map<String, Object> definition = instance.getDefinition();
        if (definition == null) {
            return;
        }
        List<Map<String, Object>> nodes = getNodes(definition);
        if (!nodes.isEmpty()) {
            initNodeExecutions(instance, nodes);
        }
    }

    /**
     * 同步执行单个节点（供 Flowable ServiceTask delegate 调用）
     * <p>注意：此方法在 Flowable 的事务中同步执行，不使用 @Async。
     * 执行完成后 Flowable 会自动流转到下一节点。
     */
    public void executeNodeSync(WorkflowInstance instance, String nodeId, Map<String, Object> previousOutput) {
        Map<String, Object> definition = instance.getDefinition();
        List<Map<String, Object>> nodes = getNodes(definition);
        List<Map<String, Object>> edges = getEdges(definition);
        Map<String, Object> nodeDef = findNodeDef(nodeId, nodes);
        if (nodeDef == null) {
            log.warn("节点定义不存在: instanceId={}, nodeId={}", instance.getId(), nodeId);
            return;
        }

        String nodeType = str(nodeDef, "type");
        WorkflowNodeExecution nodeExec = findNodeExecution(instance.getId(), nodeId);
        if (nodeExec == null) {
            log.warn("节点执行记录不存在: instanceId={}, nodeId={}", instance.getId(), nodeId);
            return;
        }

        nodeExec.setInputData(contextAssembler.buildInputData(instance, previousOutput));
        nodeExec.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());
        nodeExec.setStartedAt(LocalDateTime.now());
        nodeExec.setCompletedAt(null);
        nodeExec.setErrorMessage(null);
        clearNullableNodeExecutionColumns(nodeExec.getId(), false, true, true);
        nodeExecutionMapper.updateById(nodeExec);
        updateCurrentNode(instance, nodeId, nodeType, str(nodeDef, "label"));

        log.info("同步执行节点: instanceId={}, nodeId={}, nodeType={}", instance.getId(), nodeId, nodeType);

        try {
            switch (nodeType) {
                case "trigger_manual", "trigger_cron", "trigger_event" ->
                        handleTriggerNodeSync(nodeExec);
                case "notification" ->
                        handleNotificationNodeSync(instance, nodeExec, nodeDef);
                case "deviation_analysis" ->
                        handleDeviationAnalysisNodeSync(instance, nodeExec);
                case "quality_report" ->
                        handleQualityReportNodeSync(instance, nodeExec);
                case "end" ->
                        handleEndNode(instance, nodeExec);
                default -> {
                    log.info("同步模式 - 未特殊处理的节点类型，自动完成: nodeType={}", nodeType);
                    completeNodeExecution(nodeExec, Map.of("auto", true));
                    saveNodeOutput(instance, nodeId, Map.of("auto", true));
                }
            }
        } catch (Exception e) {
            log.error("同步节点执行异常: instanceId={}, nodeId={}", instance.getId(), nodeId, e);
            handleNodeFailure(instance, nodeExec, nodeDef,
                    StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName(),
                    new LinkedHashMap<>(), null);
        }
    }

    /**
     * 异步执行单个节点（供 Flowable ReceiveTask/UserTask listener 调用）
     * <p>ReceiveTask 到达时 Flowable 停住，本方法异步执行节点逻辑，
     * 完成后通过 FlowableWorkflowBridge.triggerReceiveTask() 通知 Flowable 继续。
     */
    public void executeNodeAsync(WorkflowInstance instance, String nodeId, Map<String, Object> previousOutput) {
        Map<String, Object> definition = instance.getDefinition();
        List<Map<String, Object>> nodes = getNodes(definition);
        List<Map<String, Object>> edges = getEdges(definition);
        // 复用原有的 driveNode 异步方法
        self.driveNodeForFlowable(instance, nodeId, nodes, edges, previousOutput);
    }

    /**
     * Flowable 模式下的异步节点驱动（不调用 advanceWorkflow，由 Flowable 引擎流转）
     */
    @Async("agentExecutorPool")
    public void driveNodeForFlowable(WorkflowInstance instance, String nodeId,
                                      List<Map<String, Object>> nodes, List<Map<String, Object>> edges,
                                      Map<String, Object> previousOutput) {
        Map<String, Object> nodeDef = findNodeDef(nodeId, nodes);
        if (nodeDef == null) {
            log.warn("节点定义不存在: instanceId={}, nodeId={}", instance.getId(), nodeId);
            return;
        }

        String nodeType = str(nodeDef, "type");
        WorkflowNodeExecution nodeExec = findNodeExecution(instance.getId(), nodeId);
        if (nodeExec == null) {
            log.warn("节点执行记录不存在: instanceId={}, nodeId={}", instance.getId(), nodeId);
            return;
        }

        nodeExec.setInputData(contextAssembler.buildInputData(instance, previousOutput));
        nodeExec.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());
        nodeExec.setStartedAt(LocalDateTime.now());
        nodeExec.setCompletedAt(null);
        nodeExec.setErrorMessage(null);
        clearNullableNodeExecutionColumns(nodeExec.getId(), false, true, true);
        nodeExecutionMapper.updateById(nodeExec);
        updateCurrentNode(instance, nodeId, nodeType, str(nodeDef, "label"));

        log.info("Flowable 模式异步执行节点: instanceId={}, nodeId={}, nodeType={}", instance.getId(), nodeId, nodeType);

        try {
            switch (nodeType) {
                case "agent" ->
                        handleAgentNode(instance, nodeExec, nodeDef);
                case "document" ->
                        handleDocumentNode(instance, nodeExec, nodeDef);
                case "human_review" ->
                        handleHumanReviewNode(instance, nodeExec, nodeDef);
                default -> {
                    log.info("Flowable 异步模式 - 非预期的节点类型: nodeType={}", nodeType);
                    completeNodeExecution(nodeExec, Map.of("auto", true));
                    saveNodeOutput(instance, nodeId, Map.of("auto", true));
                    // 对于非预期节点，trigger Flowable 继续
                    boolean flowableTriggered = triggerFlowableContinue(instance, nodeId);
                    if (!flowableTriggered) {
                        log.warn("Flowable 节点续转失败，降级为本地推进: instanceId={}, nodeId={}",
                                instance.getId(), nodeId);
                        advanceWorkflow(instance.getId(), nodeId, Map.of("auto", true));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Flowable 异步节点执行异常: instanceId={}, nodeId={}", instance.getId(), nodeId, e);
            handleNodeFailure(instance, nodeExec, nodeDef,
                    StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName(),
                    new LinkedHashMap<>(), null);
        }
    }

    /**
     * 通知 Flowable 异步节点完成，继续流转（ReceiveTask trigger）
     */
    public boolean triggerFlowableContinue(WorkflowInstance instance, String nodeId) {
        if (instance != null && StringUtils.hasText(instance.getProcessInstanceId())) {
            return flowableBridge.triggerReceiveTask(instance.getProcessInstanceId(), nodeId);
        }
        return false;
    }

    // ---- 同步模式的节点处理器（不调用 advanceWorkflow） ----

    private void handleTriggerNodeSync(WorkflowNodeExecution nodeExec) {
        Map<String, Object> output = Map.of("triggered", true, "triggeredAt", LocalDateTime.now().toString());
        completeNodeExecution(nodeExec, output);
        saveNodeOutput(instanceMapper.selectById(nodeExec.getInstanceId()), nodeExec.getNodeId(), output);
    }

    private void handleNotificationNodeSync(WorkflowInstance instance, WorkflowNodeExecution nodeExec,
                                             Map<String, Object> nodeDef) {
        Map<String, Object> config = getConfig(nodeDef);
        Map<String, Object> output = workflowNotificationService.sendWorkflowCompletedNotification(instance, nodeExec, config);
        completeNodeExecution(nodeExec, output);
        saveNodeOutput(instance, nodeExec.getNodeId(), output);
    }

    private void handleDeviationAnalysisNodeSync(WorkflowInstance instance, WorkflowNodeExecution nodeExec) {
        Map<String, Object> output = deviationAnalysisHandler.analyze(instance);
        completeNodeExecution(nodeExec, output);
        saveNodeOutput(instance, nodeExec.getNodeId(), output);
    }

    private void handleQualityReportNodeSync(WorkflowInstance instance, WorkflowNodeExecution nodeExec) {
        Map<String, Object> report = qualityReportHandler.generateReport(instance);
        Map<String, Object> output = new HashMap<>();
        output.put("qualityReport", report);
        output.put("reportGeneratedAt", LocalDateTime.now().toString());
        completeNodeExecution(nodeExec, output);
        saveNodeOutput(instance, nodeExec.getNodeId(), output);
    }

    /**
     * 某节点完成后，推进到下一个节点（approve 操作调用此方法）
     */
    @Transactional(rollbackFor = Exception.class)
    public void advanceWorkflow(String instanceId, String completedNodeId, Map<String, Object> outputData) {
        WorkflowInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            log.warn("工作流实例不存在: instanceId={}", instanceId);
            return;
        }
        if (!WorkflowInstanceStatusEnum.RUNNING.getCode().equals(instance.getStatus())) {
            log.info("工作流实例不在运行中，跳过推进: instanceId={}, status={}", instanceId, instance.getStatus());
            return;
        }

        Map<String, Object> definition = instance.getDefinition();
        List<Map<String, Object>> nodes = getNodes(definition);
        List<Map<String, Object>> edges = getEdges(definition);

        saveNodeOutput(instance, completedNodeId, outputData);
        Map<String, Object> handoffOutput = resolveHandoffPayload(outputData);

        List<String> nextNodeIds = findNextNodes(completedNodeId, edges);
        if (nextNodeIds.isEmpty()) {
            log.info("节点 {} 没有后继节点，工作流自然结束: instanceId={}", completedNodeId, instanceId);
            completeWorkflowInstance(instance.getId(), WorkflowInstanceStatusEnum.COMPLETED.getCode(), completedNodeId);
            return;
        }

        for (String nextNodeId : nextNodeIds) {
            Map<String, Object> nextNodeDef = findNodeDef(nextNodeId, nodes);
            updateCurrentNode(instance, nextNodeId, str(nextNodeDef, "type"), str(nextNodeDef, "label"));
            driveNodeAfterCommit(instance, nextNodeId, nodes, edges, handoffOutput);
        }
    }

    /**
     * 从暂停节点恢复工作流。
     * 质量问题恢复场景下，需要先把实例与节点状态恢复为可推进状态，再沿既有边推进到后继节点。
     */
    @Transactional(rollbackFor = Exception.class)
    public void resumePausedWorkflow(String instanceId, String pausedNodeId, Map<String, Object> overrideData) {
        WorkflowInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            throw new IllegalStateException("工作流实例不存在: " + instanceId);
        }
        WorkflowNodeExecution nodeExec = findNodeExecution(instanceId, pausedNodeId);
        if (nodeExec == null) {
            throw new IllegalStateException("工作流节点不存在: " + pausedNodeId);
        }
        Map<String, Object> resumedOutput = mergeNodeOutputData(nodeExec.getOutputData(), overrideData);
        nodeExec.setStatus(WorkflowInstanceStatusEnum.COMPLETED.getCode());
        nodeExec.setErrorMessage(null);
        if (nodeExec.getCompletedAt() == null) {
            nodeExec.setCompletedAt(LocalDateTime.now());
        }
        nodeExec.setOutputData(prepareStructuredOutputData(nodeExec, resumedOutput));
        nodeExecutionMapper.updateById(nodeExec);

        instance.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());
        instance.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(instance);
        syncSpecLifecycle(instance.getSpecId(), nodeExec.getNodeId(), nodeExec.getNodeType(),
                nodeExec.getNodeLabel(), WorkflowInstanceStatusEnum.RUNNING.getCode(), null);

        if (resumePausedFlowableNode(instance, pausedNodeId, resumedOutput)) {
            return;
        }
        advanceWorkflow(instanceId, pausedNodeId, resumedOutput);
    }

    @Transactional(rollbackFor = Exception.class)
    public void rollbackToNode(String instanceId, String targetNodeId, Map<String, Object> previousOutput) {
        WorkflowInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            return;
        }
        Map<String, Object> definition = instance.getDefinition();
        List<Map<String, Object>> nodes = getNodes(definition);
        List<Map<String, Object>> edges = getEdges(definition);
        Map<String, Object> targetNodeDef = findNodeDef(targetNodeId, nodes);
        if (targetNodeDef == null) {
            throw new IllegalStateException("回滚节点不存在: " + targetNodeId);
        }
        WorkflowNodeExecution targetExecution = findNodeExecution(instanceId, targetNodeId);
        if (targetExecution == null) {
            throw new IllegalStateException("回滚节点执行记录不存在: " + targetNodeId);
        }

        targetExecution.setStatus(WorkflowInstanceStatusEnum.PENDING.getCode());
        targetExecution.setErrorMessage(null);
        targetExecution.setStartedAt(null);
        targetExecution.setCompletedAt(null);
        targetExecution.setOutputData(new HashMap<>());
        nodeExecutionMapper.update(
                null,
                new UpdateWrapper<WorkflowNodeExecution>()
                        .eq("id", targetExecution.getId())
                        .set("status", targetExecution.getStatus())
                        .set("error_message", null)
                        .set("started_at", null)
                        .set("completed_at", null)
                        .set("updated_at", LocalDateTime.now())
        );
        nodeExecutionMapper.updateById(targetExecution);

        updateCurrentNode(instance, targetNodeId, str(targetNodeDef, "type"), str(targetNodeDef, "label"));
        driveNodeAfterCommit(instance, targetNodeId, nodes, edges, previousOutput == null ? Map.of() : previousOutput);
    }

    /**
     * Agent 节点异步执行完毕回调 — 由 WorkflowAgentCallback 调用
     */
    public void onAgentNodeCompleted(String instanceId, String nodeId, String agentExecutionStatus, String result) {
        WorkflowNodeExecution nodeExec = findNodeExecution(instanceId, nodeId);
        if (nodeExec == null) {
            log.warn("节点执行记录不存在: instanceId={}, nodeId={}", instanceId, nodeId);
            return;
        }
        WorkflowInstance instance = instanceMapper.selectById(instanceId);
        Map<String, Object> nodeDef = instance != null
                ? findNodeDef(nodeId, getNodes(instance.getDefinition()))
                : null;

        Map<String, Object> outputData = new LinkedHashMap<>();
        outputData.put("agentStatus", agentExecutionStatus);
        outputData.put("result", result);
        outputData.put("completedAt", LocalDateTime.now().toString());

        if (AgentExecutionStatusEnum.PAUSED.getCode().equals(agentExecutionStatus)) {
            nodeExec.setStatus(WorkflowInstanceStatusEnum.PAUSED.getCode());
            nodeExec.setErrorMessage(StringUtils.hasText(result) ? result : "Agent 执行暂停，等待人工处理");
            nodeExec.setOutputData(prepareStructuredOutputData(nodeExec, outputData));
            nodeExecutionMapper.updateById(nodeExec);
            pauseWorkflowInstance(instance, nodeExec, nodeExec.getErrorMessage());
            log.warn("Agent节点执行暂停: instanceId={}, nodeId={}, agentStatus={}", instanceId, nodeId, agentExecutionStatus);
            return;
        }

        boolean agentSucceeded = AgentExecutionStatusEnum.COMPLETED.getCode().equals(agentExecutionStatus)
                || AgentExecutionStatusEnum.STOPPED.getCode().equals(agentExecutionStatus);

        if (agentSucceeded) {
            if (instance != null) {
                if (WorkflowInstanceStatusEnum.PAUSED.getCode().equals(instance.getStatus())) {
                    instance.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());
                    instance.setUpdatedAt(LocalDateTime.now());
                    instanceMapper.updateById(instance);
                }
                Map<String, Object> nodeConfig = getConfig(nodeDef);

                // 输出安全合规检查（安全检查优先于质量检查，避免不安全内容传递给下游）
                SecurityCheckDecisionVO outputSecurityDecision = evaluateOutputSecurityForNode(instance, nodeExec, result);
                if (outputSecurityDecision != null) {
                    String secDecision = outputSecurityDecision.getDecision();
                    String secMessage = StringUtils.hasText(outputSecurityDecision.getMessage())
                            ? outputSecurityDecision.getMessage() : "节点输出命中安全策略";
                    outputData.put("outputSecurityDecision", secDecision);
                    outputData.put("outputSecurityMessage", secMessage);
                    if (SecurityComplianceConstant.DECISION_BLOCK.equals(secDecision)) {
                        nodeExec.setStatus(WorkflowInstanceStatusEnum.FAILED.getCode());
                        nodeExec.setErrorMessage("输出安全检查阻断: " + secMessage);
                        nodeExec.setCompletedAt(LocalDateTime.now());
                        nodeExec.setOutputData(prepareStructuredOutputData(nodeExec, outputData));
                        nodeExecutionMapper.updateById(nodeExec);
                        failWorkflowInstance(instanceId, nodeId, "输出安全检查阻断: " + secMessage);
                        log.error("节点输出安全检查阻断: instanceId={}, nodeId={}, message={}", instanceId, nodeId, secMessage);
                        return;
                    }
                    if (SecurityComplianceConstant.DECISION_PAUSE.equals(secDecision)) {
                        nodeExec.setStatus(WorkflowInstanceStatusEnum.PAUSED.getCode());
                        nodeExec.setErrorMessage("输出安全检查暂停: " + secMessage);
                        nodeExec.setOutputData(prepareStructuredOutputData(nodeExec, outputData));
                        nodeExecutionMapper.updateById(nodeExec);
                        pauseWorkflowInstance(instance, nodeExec, "输出安全检查暂停: " + secMessage);
                        log.warn("节点输出安全检查暂停: instanceId={}, nodeId={}, message={}", instanceId, nodeId, secMessage);
                        return;
                    }
                    if (SecurityComplianceConstant.DECISION_WARN.equals(secDecision)) {
                        log.warn("节点输出安全检查警告: instanceId={}, nodeId={}, message={}", instanceId, nodeId, secMessage);
                    }
                }

                Map<String, Object> qualityOutput = builtinQualityAssuranceService.analyzeAgentNode(instance, nodeExec, nodeConfig, result);
                if (qualityOutput != null && !qualityOutput.isEmpty()) {
                    outputData.putAll(qualityOutput);
                }
                Map<String, Object> runtimeMetadata = buildArtifactRuntimeMetadata(nodeExec, qualityOutput);
                if (!runtimeMetadata.isEmpty()) {
                    outputData.putAll(runtimeMetadata);
                }
                Map<String, Object> artifactData = workflowArtifactService.persistAgentArtifactIfNecessary(
                        instance, nodeExec, nodeConfig, result, runtimeMetadata);
                if (artifactData != null && !artifactData.isEmpty()) {
                    outputData.putAll(artifactData);
                }
                syncArtifactVariables(instance, nodeConfig, artifactData);
                QualityGateDecision qualityGateDecision =
                        builtinQualityAssuranceService.evaluateWorkflowNodeGate(
                                qualityOutput != null ? qualityOutput : Map.of(), nodeConfig);
                if (qualityGateDecision == null) {
                    qualityGateDecision = QualityGateDecision.pass("未命中质量闸门");
                }
                outputData.putAll(qualityGateDecision.toOutputData());
                if (qualityGateDecision.pauseWorkflow()) {
                    QualityIssue qualityIssue = createWorkflowQualityIssue(instance, nodeExec, nodeConfig, qualityOutput, qualityGateDecision);
                    bindWorkflowQualityIssue(outputData, qualityIssue);
                    nodeExec.setStatus(WorkflowInstanceStatusEnum.PAUSED.getCode());
                    nodeExec.setErrorMessage(qualityGateDecision.message());
                    nodeExec.setOutputData(prepareStructuredOutputData(nodeExec, outputData));
                    nodeExecutionMapper.updateById(nodeExec);
                    pauseWorkflowInstance(instance, nodeExec, qualityGateDecision.message());
                    notifyWorkflowQualityGate(instance, nodeExec, qualityGateDecision, qualityIssue);
                    log.warn("Agent节点命中质量闸门暂停: instanceId={}, nodeId={}, reason={}",
                            instanceId, nodeId, qualityGateDecision.message());
                    return;
                }
                if (qualityGateDecision.failWorkflow()) {
                    QualityIssue qualityIssue = createWorkflowQualityIssue(instance, nodeExec, nodeConfig, qualityOutput, qualityGateDecision);
                    bindWorkflowQualityIssue(outputData, qualityIssue);
                    nodeExec.setStatus(WorkflowInstanceStatusEnum.FAILED.getCode());
                    nodeExec.setErrorMessage(qualityGateDecision.message());
                    nodeExec.setCompletedAt(LocalDateTime.now());
                    nodeExec.setOutputData(prepareStructuredOutputData(nodeExec, outputData));
                    nodeExecutionMapper.updateById(nodeExec);
                    failWorkflowInstance(instanceId, nodeId, qualityGateDecision.message());
                    notifyWorkflowQualityGate(instance, nodeExec, qualityGateDecision, qualityIssue);
                    log.error("Agent节点命中质量闸门失败: instanceId={}, nodeId={}, reason={}",
                            instanceId, nodeId, qualityGateDecision.message());
                    return;
                }
            }
            completeNodeExecution(nodeExec, outputData);
            // Flowable 模式：触发 ReceiveTask 继续流转；降级模式：手动推进
            if (instance != null && StringUtils.hasText(instance.getProcessInstanceId())) {
                saveNodeOutput(instance, nodeId, outputData);
                boolean flowableTriggered = triggerFlowableContinue(instance, nodeId);
                if (!flowableTriggered) {
                    log.warn("Flowable 续转失败，降级为本地推进: instanceId={}, nodeId={}", instanceId, nodeId);
                    advanceWorkflow(instanceId, nodeId, outputData);
                }
            } else {
                advanceWorkflow(instanceId, nodeId, outputData);
            }
        } else {
            String failureMessage = StringUtils.hasText(result) ? result : "Agent执行失败: " + agentExecutionStatus;
            handleNodeFailure(instance, nodeExec, nodeDef, failureMessage, outputData, agentExecutionStatus);
        }
    }

    // =====================================================================
    //  核心节点驱动
    // =====================================================================

    /**
     * 驱动单个节点执行（根据节点类型分发）
     */
    @Async("agentExecutorPool")
    public void driveNode(WorkflowInstance instance, String nodeId,
                          List<Map<String, Object>> nodes, List<Map<String, Object>> edges,
                          Map<String, Object> previousOutput) {
        Map<String, Object> nodeDef = findNodeDef(nodeId, nodes);
        if (nodeDef == null) {
            log.warn("节点定义不存在: instanceId={}, nodeId={}", instance.getId(), nodeId);
            return;
        }

        String nodeType = str(nodeDef, "type");
        WorkflowNodeExecution nodeExec = findNodeExecution(instance.getId(), nodeId);
        if (nodeExec == null) {
            log.warn("节点执行记录不存在: instanceId={}, nodeId={}", instance.getId(), nodeId);
            return;
        }

        nodeExec.setInputData(contextAssembler.buildInputData(instance, previousOutput));
        nodeExec.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());
        nodeExec.setStartedAt(LocalDateTime.now());
        nodeExec.setCompletedAt(null);
        nodeExec.setErrorMessage(null);
        clearNullableNodeExecutionColumns(nodeExec.getId(), false, true, true);
        nodeExecutionMapper.updateById(nodeExec);

        log.info("开始执行节点: instanceId={}, nodeId={}, nodeType={}", instance.getId(), nodeId, nodeType);

        try {
            switch (nodeType) {
                case "trigger_manual", "trigger_cron", "trigger_event" ->
                        handleTriggerNode(instance, nodeExec);
                case "agent" ->
                        handleAgentNode(instance, nodeExec, nodeDef);
                case "document" ->
                        handleDocumentNode(instance, nodeExec, nodeDef);
                case "human_review" ->
                        handleHumanReviewNode(instance, nodeExec, nodeDef);
                case "notification" ->
                        handleNotificationNode(instance, nodeExec, nodeDef);
                case "deviation_analysis" ->
                        handleDeviationAnalysisNode(instance, nodeExec);
                case "quality_report" ->
                        handleQualityReportNode(instance, nodeExec);
                case "end" ->
                        handleEndNode(instance, nodeExec);
                default -> {
                    log.info("未特殊处理的节点类型，自动完成: nodeType={}", nodeType);
                    completeNodeExecution(nodeExec, Map.of("auto", true));
                    advanceWorkflow(instance.getId(), nodeId, Map.of("auto", true));
                }
            }
        } catch (Exception e) {
            log.error("节点执行异常: instanceId={}, nodeId={}, nodeType={}", instance.getId(), nodeId, nodeType, e);
            String failureMessage = StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
            handleNodeFailure(instance, nodeExec, nodeDef, failureMessage, new LinkedHashMap<>(), null);
        }
    }

    // =====================================================================
    //  各节点类型处理
    // =====================================================================

    private void handleTriggerNode(WorkflowInstance instance, WorkflowNodeExecution nodeExec) {
        Map<String, Object> output = Map.of("triggered", true, "triggeredAt", LocalDateTime.now().toString());
        completeNodeExecution(nodeExec, output);
        advanceWorkflow(instance.getId(), nodeExec.getNodeId(), output);
    }

    private void handleDocumentNode(WorkflowInstance instance, WorkflowNodeExecution nodeExec,
                                    Map<String, Object> nodeDef) {
        Map<String, Object> config = getConfig(nodeDef);
        Map<String, Object> output = nodeExec.getOutputData() != null
                ? new HashMap<>(nodeExec.getOutputData()) : new HashMap<>();
        output.put("documentKey", str(config, "documentKey"));
        output.put("waitingForSubmit", true);
        nodeExec.setStatus(WorkflowInstanceStatusEnum.PENDING.getCode());
        nodeExec.setOutputData(prepareStructuredOutputData(nodeExec, output));
        nodeExecutionMapper.updateById(nodeExec);
        log.info("文档节点进入待编辑状态: instanceId={}, nodeId={}, documentKey={}",
                instance.getId(), nodeExec.getNodeId(), str(config, "documentKey"));
    }

    private void handleAgentNode(WorkflowInstance instance, WorkflowNodeExecution nodeExec,
                                  Map<String, Object> nodeDef) {
        Map<String, Object> config = getConfig(nodeDef);
        String agentId = str(config, "agentId");
        String taskInstruction = str(config, "taskInstruction");

        if (!StringUtils.hasText(agentId)) {
            throw new IllegalStateException("Agent节点未绑定Agent，无法执行: " + nodeExec.getNodeId());
        }

        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new IllegalStateException("Agent不存在: " + agentId);
        }

        Map<String, Object> promptConfig = new LinkedHashMap<>(config);
        promptConfig.putAll(buildWorkflowAgentPromptFacts(agent));
        String fullInstruction = contextAssembler.buildAgentExecutionPrompt(
                instance,
                nodeExec.getNodeLabel(),
                promptConfig,
                nodeExec.getInputData()
        );

        SecurityCheckDecisionVO securityDecision = securityRuntimeGuardServiceProvider.getObject().evaluate(
                buildNodeSecurityCheckRequest(instance, nodeExec, agentId, fullInstruction),
                null
        );
        if (securityDecision != null && SecurityComplianceConstant.DECISION_PAUSE.equals(securityDecision.getDecision())) {
            nodeExec.setStatus(WorkflowInstanceStatusEnum.PAUSED.getCode());
            nodeExec.setErrorMessage(securityDecision.getMessage());
            nodeExec.setOutputData(Map.of("securityDecision", securityDecision));
            nodeExecutionMapper.updateById(nodeExec);
            pauseWorkflowInstance(instance, nodeExec, securityDecision.getMessage());
            return;
        }
        if (securityDecision != null && SecurityComplianceConstant.DECISION_BLOCK.equals(securityDecision.getDecision())) {
            nodeExec.setStatus(WorkflowInstanceStatusEnum.FAILED.getCode());
            nodeExec.setErrorMessage(securityDecision.getMessage());
            nodeExec.setOutputData(Map.of("securityDecision", securityDecision));
            nodeExec.setCompletedAt(LocalDateTime.now());
            nodeExecutionMapper.updateById(nodeExec);
            failWorkflowInstance(instance.getId(), nodeExec.getNodeId(), securityDecision.getMessage());
            return;
        }

        AgentExecution agentExecution = new AgentExecution();
        agentExecution.setAgentId(agentId);
        agentExecution.setTenantId(instance.getTenantId());
        agentExecution.setInputPrompt(fullInstruction);
        agentExecution.setInputContext(nodeExec.getInputData());
        agentExecution.setSpecId(instance.getSpecId());
        agentExecution.setAiModel(agent.getAiModel());
        agentExecution.setStatus(AgentExecutionStatusEnum.QUEUED.getCode());
        agentExecution.setCreatedAt(LocalDateTime.now());
        agentExecutionMapper.insert(agentExecution);

        nodeExec.setAgentExecutionId(agentExecution.getId());
        nodeExecutionMapper.updateById(nodeExec);

        final String instanceId = instance.getId();
        final String nodeId = nodeExec.getNodeId();
        final String nodeLabel = nodeExec.getNodeLabel();
        final String tenantId = instance.getTenantId();
        final String executionId = agentExecution.getId();
        final String teamAgentId = StringUtils.hasText(str(config, "teamAgentId"))
                ? str(config, "teamAgentId") : instanceId;
        final int maxRounds = resolveWorkflowAgentMaxRounds(config);
        final int maxToolCallsPerRound = resolveWorkflowAgentMaxToolCallsPerRound(config);

        AgentExecutionContext ctx = AgentExecutionContext.builder()
                .executionId(executionId)
                .agentId(agentId)
                .tenantId(tenantId)
                .inputPrompt(fullInstruction)
                .model(agent.getAiModel())
                .agentModelType(agent.getAiModelType())
                .agentModelGroupId(agent.getAiModelGroupId())
                .inputContext(nodeExec.getInputData())
                .maxMessages(resolveWorkflowAgentMaxMessages(maxRounds, maxToolCallsPerRound))
                .maxRounds(maxRounds)
                .maxToolCallsPerRound(maxToolCallsPerRound)
                .build();

        agentRuntimeOrchestratorProvider.getObject().execute(ctx)
                .thenAccept(result -> {
                    String callbackStatus = result != null && StringUtils.hasText(result.getStatus())
                            ? result.getStatus() : AgentExecutionStatusEnum.FAILED.getCode();
                    String callbackResult = resolveAgentCallbackPayload(result, callbackStatus);
                    String callbackConversationId = result != null ? result.getConversationId() : null;
                    try {
                        onAgentNodeCompleted(instanceId, nodeId, callbackStatus, callbackResult);
                    } catch (Exception callbackException) {
                        log.error("Agent节点回调处理异常: instanceId={}, nodeId={}", instanceId, nodeId, callbackException);
                        onAgentNodeCompletedSafely(instanceId, nodeId,
                                AgentExecutionStatusEnum.FAILED.getCode(), "Agent回调处理异常: " + callbackException.getMessage());
                        return;
                    }
                    agentContextPublisher.publishAgentOutput(
                            teamAgentId, agentId, executionId, instanceId,
                            nodeId, nodeLabel, callbackStatus, callbackResult, tenantId, callbackConversationId);
                })
                .exceptionally(ex -> {
                    String errorMessage = resolveThrowableMessage(ex);
                    log.error("Agent节点执行异常: instanceId={}, nodeId={}, error={}", instanceId, nodeId, errorMessage, ex);
                    onAgentNodeCompletedSafely(instanceId, nodeId,
                            AgentExecutionStatusEnum.FAILED.getCode(), "Agent执行异常: " + errorMessage);
                    return null;
                });

        log.info("Agent节点已触发异步执行: instanceId={}, nodeId={}, agentId={}, executionId={}",
                instance.getId(), nodeId, agentId, executionId);
    }

    private String resolveAgentCallbackPayload(AgentExecutionResult result, String callbackStatus) {
        if (result == null) {
            return "Agent执行返回空结果";
        }
        if (StringUtils.hasText(result.getOutputResult())) {
            return result.getOutputResult();
        }
        if (!AgentExecutionStatusEnum.COMPLETED.getCode().equals(callbackStatus)
                && !AgentExecutionStatusEnum.STOPPED.getCode().equals(callbackStatus)
                && StringUtils.hasText(result.getErrorMessage())) {
            return result.getErrorMessage();
        }
        if (StringUtils.hasText(result.getErrorMessage())) {
            return result.getErrorMessage();
        }
        return "Agent执行返回空结果";
    }

    private void pauseWorkflowInstance(WorkflowInstance instance, WorkflowNodeExecution nodeExec, String reason) {
        if (instance == null) {
            return;
        }
        instance.setStatus(WorkflowInstanceStatusEnum.PAUSED.getCode());
        instance.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(instance);
        if (StringUtils.hasText(instance.getProcessInstanceId())) {
            flowableBridge.suspendProcess(instance.getProcessInstanceId());
        }
        syncSpecLifecycle(instance.getSpecId(), nodeExec.getNodeId(), nodeExec.getNodeType(),
                nodeExec.getNodeLabel(), WorkflowInstanceStatusEnum.PAUSED.getCode(), null);
        log.warn("工作流因 Agent 节点暂停而进入暂停状态: instanceId={}, nodeId={}, reason={}",
                instance.getId(), nodeExec.getNodeId(), reason);
    }

    private boolean resumePausedFlowableNode(WorkflowInstance instance, String pausedNodeId, Map<String, Object> resumedOutput) {
        if (instance == null || !StringUtils.hasText(instance.getProcessInstanceId())) {
            return false;
        }
        saveNodeOutput(instance, pausedNodeId, resumedOutput);

        boolean flowableTriggered = triggerFlowableContinue(instance, pausedNodeId);
        if (!flowableTriggered) {
            flowableBridge.activateProcess(instance.getProcessInstanceId());
            flowableTriggered = triggerFlowableContinue(instance, pausedNodeId);
        }
        if (flowableTriggered) {
            log.info("暂停工作流已恢复并同步 Flowable 续转: instanceId={}, nodeId={}, processInstanceId={}",
                    instance.getId(), pausedNodeId, instance.getProcessInstanceId());
            return true;
        }

        log.warn("恢复暂停工作流时 Flowable 续转失败，降级为本地推进: instanceId={}, nodeId={}",
                instance.getId(), pausedNodeId);
        return false;
    }

    private void notifyWorkflowQualityGate(WorkflowInstance instance,
                                           WorkflowNodeExecution nodeExec,
                                           QualityGateDecision qualityGateDecision,
                                           QualityIssue qualityIssue) {
        if (instance == null || nodeExec == null || qualityGateDecision == null) {
            return;
        }
        String owner = resolveSpecOwner(instance.getSpecId());
        if (!StringUtils.hasText(owner)) {
            return;
        }
        String title = qualityGateDecision.pauseWorkflow()
                ? "工作流已暂停，待处理质量阻断"
                : "工作流执行失败，质量闸门已阻断";
        String content = "工作流节点【"
                + (StringUtils.hasText(nodeExec.getNodeLabel()) ? nodeExec.getNodeLabel() : nodeExec.getNodeId())
                + "】命中质量闸门，请尽快查看并处理。原因："
                + compactText(qualityGateDecision.message(), 300);
        Map<String, Object> payload = new LinkedHashMap<>(qualityGateDecision.toOutputData());
        payload.put("workflowInstanceId", instance.getId());
        payload.put("workflowNodeId", nodeExec.getNodeId());
        payload.put("specId", instance.getSpecId());
        if (qualityIssue != null && StringUtils.hasText(qualityIssue.getId())) {
            payload.put("qualityIssueId", qualityIssue.getId());
            payload.put("qualityIssueStatus", qualityIssue.getStatus());
        }
        inAppMessageService.createMessage(
                instance.getTenantId(),
                title,
                content,
                "alert",
                qualityGateDecision.pauseWorkflow() ? "workflow_quality_gate_paused" : "workflow_quality_gate_failed",
                "workflow_quality_gate",
                nodeExec.getId(),
                resolveWorkflowQualityGateActionUrl(instance, qualityIssue),
                payload,
                List.of(owner)
        );
    }

    private String resolveWorkflowQualityGateActionUrl(WorkflowInstance instance, QualityIssue qualityIssue) {
        if (qualityIssue != null && StringUtils.hasText(qualityIssue.getId())) {
            return "/approval-center?type=quality_issue&id=" + qualityIssue.getId();
        }
        return "/workflow/" + (instance != null ? instance.getId() : "");
    }

    private void bindWorkflowQualityIssue(Map<String, Object> outputData, QualityIssue qualityIssue) {
        if (outputData == null || qualityIssue == null || !StringUtils.hasText(qualityIssue.getId())) {
            return;
        }
        outputData.put("qualityIssueId", qualityIssue.getId());
        outputData.put("qualityIssueStatus", qualityIssue.getStatus());
    }

    private QualityIssue createWorkflowQualityIssue(WorkflowInstance instance,
                                                    WorkflowNodeExecution nodeExec,
                                                    Map<String, Object> nodeConfig,
                                                    Map<String, Object> qualityOutput,
                                                    QualityGateDecision qualityGateDecision) {
        if (instance == null || nodeExec == null || qualityGateDecision == null
                || (!qualityGateDecision.pauseWorkflow() && !qualityGateDecision.failWorkflow())) {
            return null;
        }
        try {
            QualityCheckRequest request = buildWorkflowQualityIssueRequest(instance, nodeExec, nodeConfig, qualityOutput);
            QualityCheckResult result = buildWorkflowQualityIssueResult(qualityOutput, qualityGateDecision);
            return qualityIssueFeedbackService.createIssue(result, request, instance.getId(), nodeExec.getNodeId());
        } catch (Exception ex) {
            log.error("创建工作流质量问题失败: instanceId={}, nodeId={}, error={}",
                    instance.getId(), nodeExec.getNodeId(), ex.getMessage(), ex);
            return null;
        }
    }

    private QualityCheckRequest buildWorkflowQualityIssueRequest(WorkflowInstance instance,
                                                                 WorkflowNodeExecution nodeExec,
                                                                 Map<String, Object> nodeConfig,
                                                                 Map<String, Object> qualityOutput) {
        Map<String, Object> safeNodeConfig = nodeConfig == null ? Map.of() : nodeConfig;
        String executionStrategy = readString(qualityOutput, "qualityExecutionStrategy");
        if (!StringUtils.hasText(executionStrategy)) {
            executionStrategy = readString(safeNodeConfig, "executionStrategy");
        }
        if (!StringUtils.hasText(executionStrategy)) {
            executionStrategy = ExecutionStrategyEnum.SHORT_WAIT.getCode();
        }
        String artifactDocType = readString(safeNodeConfig, "artifactDocType");
        String targetContent = readString(qualityOutput, "artifactContent");
        if (!StringUtils.hasText(targetContent)) {
            targetContent = readString(qualityOutput, "summary");
        }
        if (!StringUtils.hasText(targetContent)) {
            targetContent = readString(qualityOutput, "qualitySummary");
        }
        return new QualityCheckRequest(
                instance.getSpecId(),
                targetContent,
                null,
                QualityIssueTypeEnum.DEVIATION.getCode(),
                "workflow",
                executionStrategy,
                readString(safeNodeConfig, "qualityProfileId"),
                safeNodeConfig,
                instance.getTemplateId(),
                nodeExec.getAgentExecutionId(),
                nodeExec.getNodeId(),
                nodeExec.getNodeLabel(),
                artifactDocType,
                instance.getTenantId(),
                0
        );
    }

    private QualityCheckResult buildWorkflowQualityIssueResult(Map<String, Object> qualityOutput,
                                                               QualityGateDecision qualityGateDecision) {
        List<QualityEvaluationStrategy.Finding> findings = buildWorkflowQualityFindings(qualityOutput, qualityGateDecision);
        return new QualityCheckResult(
                readString(qualityOutput, "qualityTaskId"),
                qualityGateDecision.decision(),
                findings,
                readInteger(qualityOutput != null ? qualityOutput.get("qualityScore") : null, 0),
                resolveWorkflowQualitySummary(qualityOutput, qualityGateDecision),
                readString(qualityOutput, "crossReviewId"),
                0L,
                readString(qualityOutput, "qualityExecutionStrategy"),
                resolveWorkflowQualityTaskStatus(qualityOutput),
                readInteger(qualityOutput != null ? qualityOutput.get("qualityDeviationCount") : null, findings.size()),
                readInteger(qualityOutput != null ? qualityOutput.get("qualityWarningCount") : null, 0)
        );
    }

    private List<QualityEvaluationStrategy.Finding> buildWorkflowQualityFindings(Map<String, Object> qualityOutput,
                                                                                  QualityGateDecision qualityGateDecision) {
        List<QualityEvaluationStrategy.Finding> findings = new ArrayList<>();
        String implementationEvidenceMessage = readString(qualityOutput, "implementationEvidenceMessage");
        if (StringUtils.hasText(implementationEvidenceMessage)
                && "false".equalsIgnoreCase(readString(qualityOutput, "implementationEvidencePassed"))) {
            findings.add(new QualityEvaluationStrategy.Finding(
                    "implementation_evidence",
                    qualityGateDecision.failWorkflow() ? "critical" : "warning",
                    100,
                    "IMPLEMENTATION_EVIDENCE_GUARD",
                    "实现证据校验未通过",
                    implementationEvidenceMessage,
                    readString(qualityOutput, "nodeId"),
                    "请在真实工作区补充实现类改动后重新执行节点"
            ));
        }
        if (!findings.isEmpty()) {
            return findings;
        }
        String summary = resolveWorkflowQualitySummary(qualityOutput, qualityGateDecision);
        if (!StringUtils.hasText(summary)) {
            return List.of();
        }
        findings.add(new QualityEvaluationStrategy.Finding(
                "workflow_quality_gate",
                qualityGateDecision.failWorkflow() ? "critical" : "warning",
                80,
                "WORKFLOW_QUALITY_GATE",
                "工作流质量闸门阻断",
                summary,
                readString(qualityOutput, "nodeId"),
                "请处理质量问题后再恢复工作流"
        ));
        return findings;
    }

    private String resolveWorkflowQualitySummary(Map<String, Object> qualityOutput,
                                                 QualityGateDecision qualityGateDecision) {
        String summary = readString(qualityOutput, "qualitySummary");
        if (StringUtils.hasText(summary)) {
            return summary;
        }
        return qualityGateDecision != null ? qualityGateDecision.message() : null;
    }

    private String resolveWorkflowQualityTaskStatus(Map<String, Object> qualityOutput) {
        String taskStatus = readString(qualityOutput, "qualityTaskStatus");
        if (StringUtils.hasText(taskStatus)) {
            return taskStatus;
        }
        return TaskStatusEnum.SUCCEEDED.getCode();
    }

    static int resolveWorkflowAgentMaxMessages(int maxRounds, int maxToolCallsPerRound) {
        int minimumRequired = 2 + maxRounds * (maxToolCallsPerRound + 2);
        return Math.max(WORKFLOW_AGENT_MIN_MAX_MESSAGES, minimumRequired);
    }

    private int resolveWorkflowAgentMaxRounds(Map<String, Object> config) {
        return clampInteger(readInteger(config.get("maxRounds"), DEFAULT_WORKFLOW_AGENT_MAX_ROUNDS),
                1, MAX_WORKFLOW_AGENT_MAX_ROUNDS);
    }

    private int resolveWorkflowAgentMaxToolCallsPerRound(Map<String, Object> config) {
        return clampInteger(readInteger(config.get("maxToolCallsPerRound"),
                        DEFAULT_WORKFLOW_AGENT_MAX_TOOL_CALLS_PER_ROUND),
                1, MAX_WORKFLOW_AGENT_MAX_TOOL_CALLS_PER_ROUND);
    }

    private SecurityRuntimeCheckRequest buildNodeSecurityCheckRequest(WorkflowInstance instance,
                                                                      WorkflowNodeExecution nodeExec,
                                                                      String agentId,
                                                                      String content) {
        var request = new SecurityRuntimeCheckRequest();
        request.setTenantId(instance.getTenantId());
        request.setScene(SecurityComplianceConstant.CHECK_SCENE_WORKFLOW_NODE);
        request.setDomainCode(SecurityComplianceConstant.DOMAIN_RUNTIME);
        request.setResourceType(SecurityComplianceConstant.RESOURCE_TYPE_WORKFLOW_NODE);
        request.setResourceId(nodeExec.getId());
        request.setResourceName(nodeExec.getNodeLabel());
        request.setWorkflowInstanceId(instance.getId());
        request.setWorkflowNodeId(nodeExec.getNodeId());
        request.setAgentId(agentId);
        request.setContent(content);
        request.setContext(nodeExec.getInputData());
        return request;
    }

    @SuppressWarnings("unchecked")
    private void handleHumanReviewNode(WorkflowInstance instance, WorkflowNodeExecution nodeExec,
                                        Map<String, Object> nodeDef) {
        nodeExec.setStatus(WorkflowInstanceStatusEnum.PENDING.getCode());
        Map<String, Object> config = getConfig(nodeDef);
        List<Map<String, String>> reviewers = resolveReviewers(instance, config);
        if (reviewers.isEmpty()) {
            throw new IllegalStateException("人工审核节点未解析到有效审核人: " + nodeExec.getNodeId());
        }

        ReviewSessionCreateRequest request = new ReviewSessionCreateRequest();
        request.setSpecId(instance.getSpecId());
        request.setTenantId(instance.getTenantId());
        request.setOwner(resolveSpecOwner(instance.getSpecId()));
        request.setWorkflowInstanceId(instance.getId());
        request.setWorkflowNodeId(nodeExec.getNodeId());
        request.setDocumentType(str(config, "documentKey"));
        request.setReviewers(reviewers);
        request.setDeadlineHours(readInteger(config.get("timeoutHours"), 48));
        if (StringUtils.hasText(str(config, "timeoutStrategy"))) {
            request.setTimeoutStrategy(str(config, "timeoutStrategy"));
        }
        request.setMessageTemplateId(str(config, "messageTemplateId"));
        ReviewSessionVO session = reviewSessionService().create(request);
        String reviewPath = workflowNotificationService.buildSpecReviewActionPath(
                instance.getSpecId(), instance.getId(), nodeExec.getNodeId(), session.getId());
        String reviewUrl = workflowNotificationService.buildSpecReviewActionUrl(
                instance.getSpecId(), instance.getId(), nodeExec.getNodeId(), session.getId());
        reviewSessionService().updateActionUrl(session.getId(), reviewPath);

        WorkflowNotificationService.ResolvedNotificationMessage resolvedMessage =
                workflowNotificationService.resolveHumanReviewMessage(instance, nodeExec, config, reviewUrl);
        inAppMessageService.archivePendingReviewMessages(
                instance.getTenantId(),
                instance.getSpecId(),
                nodeExec.getNodeId(),
                nodeExec.getId()
        );
        inAppMessageService.createMessage(
                instance.getTenantId(),
                resolvedMessage.message().getTitle(),
                resolvedMessage.message().getContent(),
                "todo",
                "human_review_pending",
                "workflow_human_review",
                nodeExec.getId(),
                reviewPath,
                Map.of(
                        "workflowInstanceId", instance.getId(),
                        "workflowNodeId", nodeExec.getNodeId(),
                        "reviewSessionId", session.getId(),
                        "specId", instance.getSpecId()
                ),
                reviewers.stream().map(item -> item.get("userId")).filter(StringUtils::hasText).distinct().toList()
        );

        List<Map<String, Object>> externalNotifications = workflowNotificationService
                .sendHumanReviewNotifications(instance, nodeExec, config, reviewUrl);

        Map<String, Object> outputData = nodeExec.getOutputData() != null
                ? new HashMap<>(nodeExec.getOutputData()) : new HashMap<>();
        outputData.put("reviewSessionId", session.getId());
        outputData.put("reviewActionUrl", reviewPath);
        outputData.put("reviewerCount", reviewers.size());
        if (!externalNotifications.isEmpty()) {
            outputData.put("externalNotifications", externalNotifications);
        }
        nodeExec.setReviewSessionId(session.getId());
        nodeExec.setActionUrl(reviewPath);
        nodeExec.setOutputData(prepareStructuredOutputData(nodeExec, outputData));
        nodeExecutionMapper.updateById(nodeExec);

        WorkflowInstance pausedUpdate = new WorkflowInstance();
        pausedUpdate.setId(instance.getId());
        pausedUpdate.setStatus(WorkflowInstanceStatusEnum.PAUSED.getCode());
        pausedUpdate.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(pausedUpdate);
        syncSpecLifecycle(instance.getSpecId(), nodeExec.getNodeId(), nodeExec.getNodeType(),
                nodeExec.getNodeLabel(), WorkflowInstanceStatusEnum.PAUSED.getCode(), null);

        broadcastWorkflowEvent(instance.getTenantId(), "APPROVAL_REQUEST", Map.of(
                "instanceId", instance.getId(),
                "nodeId", nodeExec.getNodeId(),
                "nodeLabel", nodeExec.getNodeLabel(),
                "reviewSessionId", session.getId()
        ));

        log.info("人工审核节点已就绪，等待审核操作: instanceId={}, nodeId={}, label={}",
                instance.getId(), nodeExec.getNodeId(), nodeExec.getNodeLabel());
    }

    private void handleNotificationNode(WorkflowInstance instance, WorkflowNodeExecution nodeExec,
                                        Map<String, Object> nodeDef) {
        Map<String, Object> config = getConfig(nodeDef);
        Map<String, Object> output = workflowNotificationService.sendWorkflowCompletedNotification(instance, nodeExec, config);
        completeNodeExecution(nodeExec, output);
        advanceWorkflow(instance.getId(), nodeExec.getNodeId(), output);
        log.info("通信渠道节点发送完成: instanceId={}, nodeId={}", instance.getId(), nodeExec.getNodeId());
    }

    private void handleDeviationAnalysisNode(WorkflowInstance instance, WorkflowNodeExecution nodeExec) {
        Map<String, Object> output = deviationAnalysisHandler.analyze(instance);
        completeNodeExecution(nodeExec, output);
        advanceWorkflow(instance.getId(), nodeExec.getNodeId(), output);
    }

    private void handleQualityReportNode(WorkflowInstance instance, WorkflowNodeExecution nodeExec) {
        Map<String, Object> report = qualityReportHandler.generateReport(instance);
        Map<String, Object> output = new HashMap<>();
        output.put("qualityReport", report);
        output.put("reportGeneratedAt", LocalDateTime.now().toString());

        log.info("质量保障报告已生成: instanceId={}", instance.getId());
        completeNodeExecution(nodeExec, output);
        advanceWorkflow(instance.getId(), nodeExec.getNodeId(), output);
    }

    private void handleEndNode(WorkflowInstance instance, WorkflowNodeExecution nodeExec) {
        List<WorkflowNodeExecution> allExecs = listNodeExecutions(instance.getId());
        boolean hasQualityReport = allExecs.stream()
                .anyMatch(e -> NodeTypeEnum.QUALITY_REPORT.getCode().equals(e.getNodeType())
                        && WorkflowInstanceStatusEnum.COMPLETED.getCode().equals(e.getStatus()));

        Map<String, Object> output = new HashMap<>();
        if (!hasQualityReport) {
            output.put("qualityReport", qualityReportHandler.generateReport(instance));
        }
        output.put("workflowCompleted", true);
        output.put("completedAt", LocalDateTime.now().toString());

        completeNodeExecution(nodeExec, output);
        saveNodeOutput(instance, nodeExec.getNodeId(), output);
        completeWorkflowInstance(instance.getId(), WorkflowInstanceStatusEnum.COMPLETED.getCode(), nodeExec.getNodeId());
        markSpecCompletedAfterWorkflow(instance);

        log.info("工作流已完成: instanceId={}", instance.getId());
    }

    // =====================================================================
    //  工具方法
    // =====================================================================

    private void initNodeExecutions(WorkflowInstance instance, List<Map<String, Object>> nodes) {
        for (Map<String, Object> node : nodes) {
            String nodeId = str(node, "id");
            String nodeType = str(node, "type");
            String nodeLabel = str(node, "label");

            long existCount = nodeExecutionMapper.selectCount(
                    new LambdaQueryWrapper<WorkflowNodeExecution>()
                            .eq(WorkflowNodeExecution::getInstanceId, instance.getId())
                            .eq(WorkflowNodeExecution::getNodeId, nodeId));
            if (existCount == 0) {
                WorkflowNodeExecution exec = new WorkflowNodeExecution();
                exec.setInstanceId(instance.getId());
                exec.setNodeId(nodeId);
                exec.setNodeType(nodeType);
                exec.setNodeLabel(nodeLabel);
                exec.setStatus(WorkflowInstanceStatusEnum.PENDING.getCode());
                exec.setInputData(new HashMap<>());
                exec.setOutputData(new HashMap<>());
                nodeExecutionMapper.insert(exec);
            }
        }
    }

    private void onAgentNodeCompletedSafely(String instanceId, String nodeId, String agentExecutionStatus, String result) {
        try {
            onAgentNodeCompleted(instanceId, nodeId, agentExecutionStatus, result);
        } catch (Exception e) {
            log.error("Agent节点失败回调再次异常: instanceId={}, nodeId={}", instanceId, nodeId, e);
        }
    }

    private ReviewSessionService reviewSessionService() {
        return reviewSessionServiceProvider.getObject();
    }

    private void handleNodeFailure(WorkflowInstance instance,
                                   WorkflowNodeExecution nodeExec,
                                   Map<String, Object> nodeDef,
                                   String failureMessage,
                                   Map<String, Object> outputData,
                                   String executionStatus) {
        Map<String, Object> safeOutput = outputData != null ? outputData : new LinkedHashMap<>();
        if (StringUtils.hasText(executionStatus) && !safeOutput.containsKey("agentStatus")) {
            safeOutput.put("agentStatus", executionStatus);
        }
        safeOutput.put("errorMessage", failureMessage);
        safeOutput.put("completedAt", LocalDateTime.now().toString());

        if (shouldSkipFailure(nodeDef)) {
            if (instance != null && WorkflowInstanceStatusEnum.PAUSED.getCode().equals(instance.getStatus())) {
                instance.setStatus(WorkflowInstanceStatusEnum.RUNNING.getCode());
                instance.setUpdatedAt(LocalDateTime.now());
                instanceMapper.updateById(instance);
            }
            safeOutput.put("skipped", true);
            safeOutput.put("failureStrategy", "skip");
            safeOutput.put("skipReason", failureMessage);
            safeOutput.put("summary", "节点 ["
                    + (StringUtils.hasText(nodeExec.getNodeLabel()) ? nodeExec.getNodeLabel() : nodeExec.getNodeId())
                    + "] 失败后按 skip 策略跳过: "
                    + compactText(failureMessage, 300));
            completeNodeExecution(nodeExec, safeOutput);
            advanceWorkflow(nodeExec.getInstanceId(), nodeExec.getNodeId(), safeOutput);
            log.warn("节点执行失败，按 skip 策略跳过: instanceId={}, nodeId={}, error={}",
                    nodeExec.getInstanceId(), nodeExec.getNodeId(), failureMessage);
            return;
        }

        nodeExec.setStatus(WorkflowInstanceStatusEnum.FAILED.getCode());
        nodeExec.setErrorMessage(failureMessage);
        nodeExec.setCompletedAt(LocalDateTime.now());
        nodeExec.setOutputData(prepareStructuredOutputData(nodeExec, safeOutput));
        nodeExecutionMapper.updateById(nodeExec);
        failWorkflowInstance(nodeExec.getInstanceId(), nodeExec.getNodeId(), failureMessage);
        log.error("节点执行失败并终止工作流: instanceId={}, nodeId={}, error={}",
                nodeExec.getInstanceId(), nodeExec.getNodeId(), failureMessage);
    }

    private void completeNodeExecution(WorkflowNodeExecution nodeExec, Map<String, Object> outputData) {
        nodeExec.setStatus(WorkflowInstanceStatusEnum.COMPLETED.getCode());
        nodeExec.setOutputData(prepareStructuredOutputData(nodeExec, outputData));
        nodeExec.setCompletedAt(LocalDateTime.now());
        nodeExecutionMapper.updateById(nodeExec);

        WorkflowInstance instance = instanceMapper.selectById(nodeExec.getInstanceId());
        if (instance != null) {
            broadcastWorkflowEvent(instance.getTenantId(), "WORKFLOW_NODE_COMPLETED", Map.of(
                    "instanceId", nodeExec.getInstanceId(),
                    "nodeId", nodeExec.getNodeId(),
                    "nodeLabel", nodeExec.getNodeLabel()
            ));
        }
    }

    private void completeWorkflowInstance(String instanceId, String status, String finalNodeId) {
        WorkflowInstance update = new WorkflowInstance();
        update.setId(instanceId);
        update.setStatus(status);
        update.setCurrentNodeId(finalNodeId);
        update.setCompletedAt(LocalDateTime.now());
        update.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(update);
        WorkflowNodeExecution finalNodeExecution = findNodeExecution(instanceId, finalNodeId);
        WorkflowInstance instance = instanceMapper.selectById(instanceId);
        if (instance != null) {
            syncSpecLifecycle(instance.getSpecId(), finalNodeId,
                    finalNodeExecution != null ? finalNodeExecution.getNodeType() : null,
                    finalNodeExecution != null ? finalNodeExecution.getNodeLabel() : null,
                    status, SpecStatusEnum.COMPLETED.getCode());
        }
    }

    private void failWorkflowInstance(String instanceId, String nodeId, String errorMessage) {
        WorkflowInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            return;
        }
        instance.setStatus(WorkflowInstanceStatusEnum.FAILED.getCode());
        instance.setCompletedAt(LocalDateTime.now());
        instance.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(instance);
        WorkflowNodeExecution nodeExecution = findNodeExecution(instanceId, nodeId);
        syncSpecLifecycle(instance.getSpecId(), nodeId,
                nodeExecution != null ? nodeExecution.getNodeType() : null,
                nodeExecution != null ? nodeExecution.getNodeLabel() : null,
                WorkflowInstanceStatusEnum.FAILED.getCode(), null);

        broadcastWorkflowEvent(instance.getTenantId(), "WORKFLOW_FAILED", Map.of(
                "instanceId", instanceId,
                "nodeId", nodeId,
                "errorMessage", StringUtils.hasText(errorMessage) ? errorMessage : "unknown error"
        ));
    }

    private void updateCurrentNode(WorkflowInstance instance, String nodeId, String nodeType, String nodeLabel) {
        WorkflowInstance update = new WorkflowInstance();
        update.setId(instance.getId());
        update.setCurrentNodeId(nodeId);
        update.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(update);
        syncSpecLifecycle(instance.getSpecId(), nodeId, nodeType, nodeLabel,
                instance.getStatus(), null);
    }

    private void markSpecCompletedAfterWorkflow(WorkflowInstance instance) {
        if (instance == null || !StringUtils.hasText(instance.getSpecId())) {
            return;
        }
        String triggerType = instance.getVariables() != null
                ? str(instance.getVariables(), "triggerType") : null;
        if (!"spec-review".equalsIgnoreCase(triggerType)) {
            return;
        }

        Spec update = new Spec();
        update.setId(instance.getSpecId());
        update.setStatus(SpecStatusEnum.COMPLETED.getCode());
        update.setUpdatedAt(LocalDateTime.now());
        specMapper.updateById(update);
    }

    private void saveNodeOutput(WorkflowInstance instance, String nodeId, Map<String, Object> outputData) {
        Map<String, Object> variables = instance.getVariables() != null
                ? new HashMap<>(instance.getVariables()) : new HashMap<>();
        Map<String, Object> handoffPayload = resolveHandoffPayload(outputData);
        variables.put(nodeId + "_output", handoffPayload);

        WorkflowInstance update = new WorkflowInstance();
        update.setId(instance.getId());
        update.setVariables(variables);
        update.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(update);

        instance.setVariables(variables);
        log.info("保存节点轻量输出: instanceId={}, nodeId={}, handoffChars={}, workflowVariablesChars={}",
                instance.getId(), nodeId, estimateChars(handoffPayload), estimateChars(variables));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeNodeOutputData(Map<String, Object> currentOutputData, Map<String, Object> overrideData) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (currentOutputData instanceof Map<?, ?> currentMap) {
            Object tracePayload = currentMap.get("tracePayload");
            if (tracePayload instanceof Map<?, ?> traceMap) {
                merged.putAll((Map<String, Object>) traceMap);
            } else {
                merged.putAll((Map<String, Object>) currentMap);
            }
        }
        if (overrideData != null && !overrideData.isEmpty()) {
            merged.putAll(overrideData);
        }
        return merged;
    }

    private String findStartNodeId(List<Map<String, Object>> nodes) {
        return nodes.stream()
                .filter(n -> {
                    String type = str(n, "type");
                    return type != null && (type.startsWith("trigger") || "start".equals(type));
                })
                .map(n -> str(n, "id"))
                .findFirst()
                .orElseGet(() -> nodes.isEmpty() ? null : str(nodes.get(0), "id"));
    }

    private List<String> findNextNodes(String currentNodeId, List<Map<String, Object>> edges) {
        return edges.stream()
                .filter(e -> currentNodeId.equals(str(e, "source")))
                .map(e -> str(e, "target"))
                .filter(StringUtils::hasText)
                .toList();
    }

    private Map<String, Object> findNodeDef(String nodeId, List<Map<String, Object>> nodes) {
        return nodes.stream()
                .filter(n -> nodeId.equals(str(n, "id")))
                .findFirst()
                .orElse(null);
    }

    private WorkflowNodeExecution findNodeExecution(String instanceId, String nodeId) {
        return listNodeExecutions(instanceId).stream()
                .filter(e -> nodeId.equals(e.getNodeId()))
                .findFirst()
                .orElse(null);
    }

    private List<WorkflowNodeExecution> listNodeExecutions(String instanceId) {
        return nodeExecutionMapper.selectList(new LambdaQueryWrapper<WorkflowNodeExecution>()
                .eq(WorkflowNodeExecution::getInstanceId, instanceId)
                .orderByAsc(WorkflowNodeExecution::getCreatedAt));
    }

    private void driveNodeAfterCommit(WorkflowInstance instance, String nodeId,
                                      List<Map<String, Object>> nodes, List<Map<String, Object>> edges,
                                      Map<String, Object> previousOutput) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            self.driveNode(instance, nodeId, nodes, edges, previousOutput);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                self.driveNode(instance, nodeId, nodes, edges, previousOutput);
            }
        });
    }

    private void broadcastWorkflowEvent(String tenantId, String eventType, Map<String, Object> data) {
        try {
            Map<String, Object> message = Map.of("type", eventType, "tenantId", tenantId, "data", data);
            rabbitTemplate.convertAndSend("sf.workflow", "workflow.event." + eventType.toLowerCase(), message);
            log.debug("发送工作流事件到MQ: type={}, tenantId={}", eventType, tenantId);
        } catch (Exception e) {
            log.warn("发送工作流事件失败: {}", e.getMessage());
        }
    }

    private String resolveThrowableMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause == null) return "unknown";
        return StringUtils.hasText(cause.getMessage()) ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getNodes(Map<String, Object> definition) {
        if (definition == null) {
            return new ArrayList<>();
        }
        Object nodes = definition.get("nodes");
        return nodes instanceof List ? (List<Map<String, Object>>) nodes : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getEdges(Map<String, Object> definition) {
        if (definition == null) {
            return new ArrayList<>();
        }
        Object edges = definition.get("edges");
        return edges instanceof List ? (List<Map<String, Object>>) edges : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getConfig(Map<String, Object> nodeDef) {
        if (nodeDef == null) {
            return new HashMap<>();
        }
        Object config = nodeDef.get("config");
        return config instanceof Map ? (Map<String, Object>) config : new HashMap<>();
    }

    private String str(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    private boolean shouldSkipFailure(Map<String, Object> nodeDef) {
        return "skip".equalsIgnoreCase(str(getConfig(nodeDef), "failureStrategy"));
    }

    /**
     * 使用 SQL 显式清空可空标量字段，避免 MyBatis-Plus 对 null 字段默认跳过更新。
     */
    private void clearNullableNodeExecutionColumns(String executionId,
                                                   boolean clearStartedAt,
                                                   boolean clearCompletedAt,
                                                   boolean clearErrorMessage) {
        if (!StringUtils.hasText(executionId)) {
            return;
        }
        UpdateWrapper<WorkflowNodeExecution> clearWrapper = new UpdateWrapper<WorkflowNodeExecution>()
                .eq("id", executionId);
        boolean needsClear = false;
        if (clearStartedAt) {
            clearWrapper.set("started_at", null);
            needsClear = true;
        }
        if (clearCompletedAt) {
            clearWrapper.set("completed_at", null);
            needsClear = true;
        }
        if (clearErrorMessage) {
            clearWrapper.set("error_message", null);
            needsClear = true;
        }
        if (!needsClear) {
            return;
        }
        clearWrapper.set("updated_at", LocalDateTime.now());
        nodeExecutionMapper.update(null, clearWrapper);
    }

    private void syncSpecLifecycle(String specId, String nodeId, String nodeType, String nodeLabel,
                                   String workflowStatus, String specStatus) {
        if (!StringUtils.hasText(specId)) {
            return;
        }
        String resolvedSpecStatus = StringUtils.hasText(specStatus)
                ? specStatus : resolveWorkflowDrivenSpecStatus(workflowStatus, nodeType, nodeId);
        Spec update = new Spec();
        update.setId(specId);
        update.setLifecycleMode(SpecLifecycleModeEnum.WORKFLOW.getCode());
        update.setCurrentNodeId(nodeId);
        update.setCurrentNodeType(nodeType);
        update.setCurrentNodeLabel(nodeLabel);
        update.setWorkflowStatusSnapshot(workflowStatus);
        if (StringUtils.hasText(resolvedSpecStatus)) {
            update.setStatus(resolvedSpecStatus);
        }
        update.setUpdatedAt(LocalDateTime.now());
        specMapper.updateById(update);
    }

    private void syncArtifactVariables(WorkflowInstance instance,
                                       Map<String, Object> nodeConfig,
                                       Map<String, Object> artifactData) {
        if (instance == null || artifactData == null || artifactData.isEmpty()) {
            return;
        }
        String artifactContent = readString(artifactData, "artifactContent");
        if (!StringUtils.hasText(artifactContent)) {
            return;
        }
        String variableKey = readString(nodeConfig, "outputVariableKey");
        if (!StringUtils.hasText(variableKey)) {
            variableKey = resolveDocumentVariableKey(readString(artifactData, "artifactDocType"));
        }
        if (!StringUtils.hasText(variableKey)) {
            return;
        }
        Map<String, Object> variables = instance.getVariables() != null
                ? new HashMap<>(instance.getVariables()) : new HashMap<>();
        variables.put(variableKey, buildArtifactVariableValue(artifactData, artifactContent));
        putIfText(variables, "artifactDeliveryType", readString(artifactData, "artifactDeliveryType"));
        putIfText(variables, "artifactDeliveryUrl", readString(artifactData, "artifactDeliveryUrl"));
        putIfText(variables, "artifactDeliveryDocumentId", readString(artifactData, "artifactDeliveryDocumentId"));
        putIfText(variables, "artifactDeliveryChannelId", readString(artifactData, "artifactDeliveryChannelId"));
        putIfText(variables, "artifactDeliveryChannelName", readString(artifactData, "artifactDeliveryChannelName"));
        WorkflowInstance update = new WorkflowInstance();
        update.setId(instance.getId());
        update.setVariables(variables);
        update.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(update);
        instance.setVariables(variables);
    }

    private void putIfText(Map<String, Object> variables, String key, String value) {
        if (variables == null || !StringUtils.hasText(key) || !StringUtils.hasText(value)) {
            return;
        }
        variables.put(key, value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> prepareStructuredOutputData(WorkflowNodeExecution nodeExec, Map<String, Object> rawOutput) {
        if (rawOutput == null) {
            rawOutput = new LinkedHashMap<>();
        }
        if (rawOutput.containsKey("tracePayload") && rawOutput.containsKey("handoffPayload")) {
            return rawOutput;
        }
        Map<String, Object> tracePayload = new LinkedHashMap<>(rawOutput);
        Map<String, Object> handoffPayload = buildHandoffPayload(nodeExec, tracePayload);
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("tracePayload", tracePayload);
        structured.put("handoffPayload", handoffPayload);
        structured.put("contextMetrics", buildContextMetrics(tracePayload, handoffPayload));
        return structured;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveHandoffPayload(Map<String, Object> outputData) {
        if (outputData == null || outputData.isEmpty()) {
            return Map.of();
        }
        Object handoff = outputData.get("handoffPayload");
        if (handoff instanceof Map<?, ?> handoffMap) {
            return new LinkedHashMap<>((Map<String, Object>) handoffMap);
        }
        return buildHandoffPayload(null, outputData);
    }

    private Map<String, Object> buildHandoffPayload(WorkflowNodeExecution nodeExec, Map<String, Object> tracePayload) {
        Map<String, Object> handoff = new LinkedHashMap<>();
        if (nodeExec != null) {
            handoff.put("nodeId", nodeExec.getNodeId());
            handoff.put("nodeType", nodeExec.getNodeType());
            handoff.put("nodeLabel", nodeExec.getNodeLabel());
            handoff.put("status", nodeExec.getStatus());
        }
        copyIfPresent(tracePayload, handoff, "agentStatus");
        copyIfPresent(tracePayload, handoff, "approvalResult");
        copyIfPresent(tracePayload, handoff, "reviewSessionId");
        copyIfPresent(tracePayload, handoff, "reviewActionUrl");
        copyIfPresent(tracePayload, handoff, "waitingForSubmit");
        copyIfPresent(tracePayload, handoff, "documentKey");
        copyIfPresent(tracePayload, handoff, "workflowCompleted");
        copyIfPresent(tracePayload, handoff, "triggered");
        copyIfPresent(tracePayload, handoff, "qualitySummary");
        copyIfPresent(tracePayload, handoff, "qualityScore");
        copyIfPresent(tracePayload, handoff, "qualityCheckedAt");
        copyIfPresent(tracePayload, handoff, "qualityTaskId");
        copyIfPresent(tracePayload, handoff, "qualityIssueId");
        copyIfPresent(tracePayload, handoff, "qualityIssueStatus");
        copyIfPresent(tracePayload, handoff, "agentExecutionId");
        copyIfPresent(tracePayload, handoff, "agentModel");
        copyIfPresent(tracePayload, handoff, "runtimeEngine");
        String summary = resolveOutputSummary(tracePayload);
        if (StringUtils.hasText(summary)) {
            handoff.put("summary", summary);
            handoff.put("nextTaskInstruction", summary);
        }
        Map<String, Object> docRef = buildDocReference(tracePayload, summary);
        if (!docRef.isEmpty()) {
            handoff.put("docRef", docRef);
            handoff.put("artifactRefs", List.of(docRef));
        }
        Map<String, Object> qualityGate = buildQualityGate(tracePayload);
        if (!qualityGate.isEmpty()) {
            handoff.put("qualityGate", qualityGate);
        }
        if (tracePayload.containsKey("qualityReport") && tracePayload.get("qualityReport") instanceof Map<?, ?> report) {
            handoff.put("qualityReportStatus", readMapString(report, "overallStatus"));
            handoff.put("recommendation", readMapString(report, "recommendation"));
        }
        return handoff;
    }

    private Map<String, Object> buildContextMetrics(Map<String, Object> tracePayload, Map<String, Object> handoffPayload) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("workflowNodeTraceChars", estimateChars(tracePayload));
        metrics.put("workflowHandoffChars", estimateChars(handoffPayload));
        return metrics;
    }

    private Map<String, Object> buildArtifactVariableValue(Map<String, Object> artifactData, String artifactContent) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("artifactId", readString(artifactData, "artifactId"));
        value.put("artifactVersion", readString(artifactData, "artifactVersion"));
        value.put("artifactType", readString(artifactData, "artifactType"));
        value.put("docId", readString(artifactData, "artifactDocId"));
        value.put("docType", readString(artifactData, "artifactDocType"));
        value.put("docVersion", readString(artifactData, "artifactDocVersion"));
        value.put("outputPath", readString(artifactData, "artifactOutputPath"));
        value.put("absolutePath", readString(artifactData, "artifactAbsolutePath"));
        value.put("deliveryType", readString(artifactData, "artifactDeliveryType"));
        value.put("deliveryUrl", readString(artifactData, "artifactDeliveryUrl"));
        value.put("deliveryDocumentId", readString(artifactData, "artifactDeliveryDocumentId"));
        value.put("deliveryChannelId", readString(artifactData, "artifactDeliveryChannelId"));
        value.put("deliveryChannelName", readString(artifactData, "artifactDeliveryChannelName"));
        value.put("rawContent", readString(artifactData, "rawContent"));
        value.put("summary", compactText(artifactContent, 800));
        value.put("contentLength", artifactContent.length());
        return value;
    }

    private Map<String, Object> buildDocReference(Map<String, Object> tracePayload, String summary) {
        Map<String, Object> docRef = new LinkedHashMap<>();
        String artifactId = readString(tracePayload, "artifactId");
        String artifactVersion = readString(tracePayload, "artifactVersion");
        String artifactType = readString(tracePayload, "artifactType");
        String docType = readString(tracePayload, "artifactDocType");
        String docVersion = readString(tracePayload, "artifactDocVersion");
        String outputPath = readString(tracePayload, "artifactOutputPath");
        String absolutePath = readString(tracePayload, "artifactAbsolutePath");
        String deliveryType = readString(tracePayload, "artifactDeliveryType");
        String deliveryUrl = readString(tracePayload, "artifactDeliveryUrl");
        String deliveryDocumentId = readString(tracePayload, "artifactDeliveryDocumentId");
        if (!StringUtils.hasText(artifactId) && !StringUtils.hasText(docType) && !StringUtils.hasText(docVersion)
                && !StringUtils.hasText(outputPath) && !StringUtils.hasText(absolutePath)
                && !StringUtils.hasText(deliveryUrl) && !StringUtils.hasText(deliveryDocumentId)) {
            return Map.of();
        }
        if (StringUtils.hasText(artifactId)) {
            docRef.put("artifactId", artifactId);
        }
        if (StringUtils.hasText(artifactVersion)) {
            docRef.put("artifactVersion", artifactVersion);
        }
        if (StringUtils.hasText(artifactType)) {
            docRef.put("artifactType", artifactType);
        }
        if (StringUtils.hasText(docType)) {
            docRef.put("docType", docType);
        }
        if (StringUtils.hasText(docVersion)) {
            docRef.put("docVersion", docVersion);
        }
        if (StringUtils.hasText(outputPath)) {
            docRef.put("outputPath", outputPath);
        }
        if (StringUtils.hasText(absolutePath)) {
            docRef.put("absolutePath", absolutePath);
        }
        if (StringUtils.hasText(deliveryType)) {
            docRef.put("deliveryType", deliveryType);
        }
        if (StringUtils.hasText(deliveryUrl)) {
            docRef.put("deliveryUrl", deliveryUrl);
        }
        if (StringUtils.hasText(deliveryDocumentId)) {
            docRef.put("deliveryDocumentId", deliveryDocumentId);
        }
        if (StringUtils.hasText(summary)) {
            docRef.put("summary", summary);
        }
        return docRef;
    }

    private Map<String, Object> buildQualityGate(Map<String, Object> tracePayload) {
        Map<String, Object> qualityGate = new LinkedHashMap<>();
        copyIfPresent(tracePayload, qualityGate, "qualityGateDecision");
        copyIfPresent(tracePayload, qualityGate, "qualityGateMessage");
        copyIfPresent(tracePayload, qualityGate, "qualityGateTriggered");
        copyIfPresent(tracePayload, qualityGate, "qualityGatePauseWorkflow");
        copyIfPresent(tracePayload, qualityGate, "qualityGateFailWorkflow");
        return qualityGate;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source == null || target == null || !source.containsKey(key) || source.get(key) == null) {
            return;
        }
        target.put(key, source.get(key));
    }

    private String resolveOutputSummary(Map<String, Object> tracePayload) {
        if (tracePayload == null || tracePayload.isEmpty()) {
            return null;
        }
        for (String preferredKey : List.of("summary", "qualitySummary", "result", "content", "message")) {
            String text = readString(tracePayload, preferredKey);
            if (StringUtils.hasText(text)) {
                return compactText(text, 800);
            }
        }
        if (tracePayload.containsKey("artifactContent")) {
            return compactText(readString(tracePayload, "artifactContent"), 800);
        }
        if (tracePayload.containsKey("qualityReport") && tracePayload.get("qualityReport") instanceof Map<?, ?> report) {
            String recommendation = readMapString(report, "recommendation");
            if (StringUtils.hasText(recommendation)) {
                return compactText(recommendation, 800);
            }
        }
        return compactText(String.valueOf(tracePayload), 800);
    }

    private String readMapString(Map<?, ?> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private int estimateChars(Object value) {
        if (value == null) {
            return 0;
        }
        return String.valueOf(value).length();
    }

    private String compactText(String value, int maxLen) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        return normalized.length() > maxLen ? normalized.substring(0, maxLen) + "..." : normalized;
    }

    private String resolveDocumentVariableKey(String artifactDocType) {
        if (!StringUtils.hasText(artifactDocType)) {
            return null;
        }
        return switch (artifactDocType) {
            case "requirements" -> "requirementsDoc";
            case "design" -> "designDoc";
            case "tasks" -> "tasksDoc";
            default -> artifactDocType + "Doc";
        };
    }

    private Map<String, Object> buildWorkflowAgentPromptFacts(Agent agent) {
        Map<String, Object> facts = new LinkedHashMap<>();
        if (agent == null) {
            return facts;
        }
        putIfHasText(facts, "boundAgentName", agent.getName());
        putIfHasText(facts, "boundAgentType", agent.getAgentType());
        putIfHasText(facts, "boundAgentStatus", agent.getStatus());
        putIfHasText(facts, "boundAgentModel", agent.getAiModel());
        if ("team".equalsIgnoreCase(agent.getAgentType())) {
            facts.put("boundRuntimeEngine", AgentRuntimeEngineEnum.TEAM_LANGGRAPH4J.getCode());
        }
        return facts;
    }

    private Map<String, Object> buildArtifactRuntimeMetadata(WorkflowNodeExecution nodeExec,
                                                             Map<String, Object> qualityOutput) {
        Map<String, Object> runtimeMetadata = new LinkedHashMap<>();
        if (nodeExec != null && StringUtils.hasText(nodeExec.getAgentExecutionId())) {
            putIfHasText(runtimeMetadata, "agentExecutionId", nodeExec.getAgentExecutionId());
            AgentExecution agentExecution = agentExecutionMapper.selectById(nodeExec.getAgentExecutionId());
            if (agentExecution != null) {
                putIfHasText(runtimeMetadata, "agentModel", agentExecution.getAiModel());
                putIfHasText(runtimeMetadata, "runtimeEngine", agentExecution.getRuntimeEngine());
            }
        }
        if (qualityOutput != null && !qualityOutput.isEmpty()) {
            copyIfPresent(qualityOutput, runtimeMetadata, "qualityScore");
            copyIfPresent(qualityOutput, runtimeMetadata, "qualityTaskId");
            copyIfPresent(qualityOutput, runtimeMetadata, "qualityCheckedAt");
            copyIfPresent(qualityOutput, runtimeMetadata, "qualitySummary");
        }
        return runtimeMetadata;
    }

    private void putIfHasText(Map<String, Object> target, String key, String value) {
        if (target == null || !StringUtils.hasText(key) || !StringUtils.hasText(value)) {
            return;
        }
        target.put(key, value);
    }

    private String resolveWorkflowDrivenSpecStatus(String workflowStatus, String nodeType, String nodeId) {
        if (WorkflowInstanceStatusEnum.COMPLETED.getCode().equals(workflowStatus)) {
            return SpecStatusEnum.COMPLETED.getCode();
        }
        if (WorkflowInstanceStatusEnum.PENDING.getCode().equals(workflowStatus) && !StringUtils.hasText(nodeId)) {
            return SpecStatusEnum.DRAFT.getCode();
        }
        if (WorkflowInstanceStatusEnum.FAILED.getCode().equals(workflowStatus)
                || WorkflowInstanceStatusEnum.CANCELLED.getCode().equals(workflowStatus)) {
            return SpecStatusEnum.FAILED.getCode();
        }
        if (NodeTypeEnum.HUMAN_REVIEW.getCode().equals(nodeType)) {
            return SpecStatusEnum.IN_PROGRESS.getCode();
        }
        return SpecStatusEnum.IN_PROGRESS.getCode();
    }

    private String readString(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private int readInteger(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignore) {
            return defaultValue;
        }
    }

    private int clampInteger(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private List<Map<String, String>> resolveReviewers(WorkflowInstance instance, Map<String, Object> config) {
        Map<String, Set<String>> reviewerRoleMap = new LinkedHashMap<>();
        for (String reviewerId : stringList(config.get("reviewerIds"))) {
            reviewerRoleMap.computeIfAbsent(reviewerId, key -> new java.util.LinkedHashSet<>()).add("指定审核人");
        }

        List<String> reviewerRoles = stringList(config.get("reviewerRoles"));
        if (!reviewerRoles.isEmpty()) {
            List<Role> roles = roleMapper.selectList(
                    new LambdaQueryWrapper<Role>()
                            .in(Role::getCode, reviewerRoles)
            );
            Map<String, Role> roleMap = roles.stream()
                    .collect(Collectors.toMap(Role::getId, item -> item, (left, right) -> left));
            if (!roleMap.isEmpty()) {
                List<UserRole> userRoles = userRoleMapper.selectList(
                        new LambdaQueryWrapper<UserRole>().in(UserRole::getRoleId, roleMap.keySet())
                );
                for (UserRole userRole : userRoles) {
                    Role role = roleMap.get(userRole.getRoleId());
                    if (role == null) {
                        continue;
                    }
                    reviewerRoleMap.computeIfAbsent(userRole.getUserId(), key -> new java.util.LinkedHashSet<>())
                            .add(StringUtils.hasText(role.getName()) ? role.getName() : role.getCode());
                }
            }
        }

        if (reviewerRoleMap.isEmpty()) {
            String fallbackReviewerId = resolveFallbackReviewerId(instance);
            if (StringUtils.hasText(fallbackReviewerId)) {
                reviewerRoleMap.computeIfAbsent(fallbackReviewerId, key -> new java.util.LinkedHashSet<>()).add("Spec负责人");
            }
        }

        return reviewerRoleMap.entrySet().stream()
                .map(entry -> {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("userId", entry.getKey());
                    item.put("role", String.join("、", entry.getValue()));
                    return item;
                })
                .toList();
    }

    private String resolveFallbackReviewerId(WorkflowInstance instance) {
        if (instance != null && StringUtils.hasText(instance.getSpecId())) {
            Spec spec = specMapper.selectById(instance.getSpecId());
            if (spec != null) {
                if (StringUtils.hasText(spec.getOwner())) {
                    return spec.getOwner();
                }
                if (StringUtils.hasText(spec.getCreatedBy())) {
                    return spec.getCreatedBy();
                }
            }
        }
        return instance != null && StringUtils.hasText(instance.getCreatedBy())
                ? instance.getCreatedBy()
                : null;
    }

    private String resolveSpecOwner(String specId) {
        if (!StringUtils.hasText(specId)) {
            return null;
        }
        Spec spec = specMapper.selectById(specId);
        return spec != null ? spec.getOwner() : null;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object source) {
        if (!(source instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item != null && StringUtils.hasText(String.valueOf(item)))
                .map(item -> String.valueOf(item).trim())
                .toList();
    }

    /** 评估工作流节点输出内容的安全合规性（PII、敏感信息、有害内容、合规风险） */
    private SecurityCheckDecisionVO evaluateOutputSecurityForNode(
            WorkflowInstance instance, WorkflowNodeExecution nodeExec, String outputContent) {
        try {
            SecurityRuntimeGuardService guard = securityRuntimeGuardServiceProvider.getIfAvailable();
            if (guard == null || !StringUtils.hasText(outputContent)) {
                return null;
            }
            SecurityRuntimeCheckRequest request = new SecurityRuntimeCheckRequest();
            request.setTenantId(instance.getTenantId());
            request.setScene(SecurityComplianceConstant.CHECK_SCENE_OUTPUT);
            request.setDomainCode(SecurityComplianceConstant.DOMAIN_RUNTIME);
            request.setResourceType(SecurityComplianceConstant.RESOURCE_TYPE_WORKFLOW_NODE);
            request.setResourceId(nodeExec.getId());
            request.setResourceName(nodeExec.getNodeLabel());
            request.setWorkflowInstanceId(instance.getId());
            request.setWorkflowNodeId(nodeExec.getNodeId());
            request.setContent(outputContent);
            SecurityCheckDecisionVO decision = guard.evaluate(request, null);
            if (decision == null || SecurityComplianceConstant.DECISION_ALLOW.equals(decision.getDecision())) {
                return null;
            }
            return decision;
        } catch (Exception e) {
            log.warn("节点输出安全检查异常，默认放行: instanceId={}, nodeId={}",
                    instance.getId(), nodeExec.getNodeId(), e);
            return null;
        }
    }
}
