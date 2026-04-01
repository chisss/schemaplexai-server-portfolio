package com.schemaplexai.service.workflow.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.NodeTypeEnum;
import com.schemaplexai.common.enums.SpecStatusEnum;
import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionEngine;
import com.schemaplexai.service.mq.AgentContextPublisher;
import com.schemaplexai.service.quality.runtime.BuiltinQualityAssuranceService;
import com.schemaplexai.service.mq.message.ApprovalNotificationMessage;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import com.schemaplexai.service.workflow.engine.assembler.WorkflowNodeContextAssembler;
import com.schemaplexai.service.workflow.engine.handler.DeviationAnalysisHandler;
import com.schemaplexai.service.workflow.engine.handler.QualityReportHandler;
import com.schemaplexai.service.workflow.runtime.WorkflowArtifactService;
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
import java.util.List;
import java.util.Map;

/**
 * 工作流节点驱动引擎
 *
 * <p>负责解析工作流定义（definition JSONB），按节点类型驱动各步骤：
 * <ul>
 *   <li>trigger/start → 自动完成，推进到下一节点</li>
 *   <li>agent → 触发 AgentExecutionEngine 异步执行，完成后继续推进</li>
 *   <li>human_review → 置为 pending，等待人工 approve/reject</li>
 *   <li>deviation_analysis → 委托 {@link DeviationAnalysisHandler} 执行</li>
 *   <li>quality_report → 委托 {@link QualityReportHandler} 生成</li>
 *   <li>end → 完成工作流实例</li>
 * </ul>
 *
 * <p>节点间上下文通过 WorkflowInstance.variables 传递（key = nodeId_output）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowNodeEngine {

    private final WorkflowInstanceMapper instanceMapper;
    private final WorkflowNodeExecutionMapper nodeExecutionMapper;
    private final AgentExecutionMapper agentExecutionMapper;
    private final AgentMapper agentMapper;
    private final SpecMapper specMapper;
    private final ObjectProvider<AgentExecutionEngine> agentExecutionEngineProvider;
    private final AgentContextPublisher agentContextPublisher;
    private final RabbitTemplate rabbitTemplate;
    private final DeviationAnalysisHandler deviationAnalysisHandler;
    private final QualityReportHandler qualityReportHandler;
    private final WorkflowNodeContextAssembler contextAssembler;
    private final WorkflowArtifactService workflowArtifactService;
    private final BuiltinQualityAssuranceService builtinQualityAssuranceService;
    private final ObjectProvider<SecurityRuntimeGuardService> securityRuntimeGuardServiceProvider;

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

        updateCurrentNode(instance.getId(), startNodeId);
        driveNodeAfterCommit(instance, startNodeId, nodes, edges, new HashMap<>());
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

        List<String> nextNodeIds = findNextNodes(completedNodeId, edges);
        if (nextNodeIds.isEmpty()) {
            log.info("节点 {} 没有后继节点，工作流自然结束: instanceId={}", completedNodeId, instanceId);
            completeWorkflowInstance(instance.getId(), WorkflowInstanceStatusEnum.COMPLETED.getCode(), completedNodeId);
            return;
        }

        for (String nextNodeId : nextNodeIds) {
            updateCurrentNode(instanceId, nextNodeId);
            driveNodeAfterCommit(instance, nextNodeId, nodes, edges, outputData);
        }
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

        Map<String, Object> outputData = new HashMap<>();
        outputData.put("agentStatus", agentExecutionStatus);
        outputData.put("result", result);
        outputData.put("agentResult", result);
        outputData.put("completedAt", LocalDateTime.now().toString());

        boolean agentSucceeded = AgentExecutionStatusEnum.COMPLETED.getCode().equals(agentExecutionStatus)
                || AgentExecutionStatusEnum.STOPPED.getCode().equals(agentExecutionStatus);

        if (agentSucceeded) {
            WorkflowInstance instance = instanceMapper.selectById(instanceId);
            if (instance != null) {
                outputData.putAll(workflowArtifactService.persistAgentArtifactIfNecessary(instance, nodeExec, result));
                outputData.putAll(builtinQualityAssuranceService.analyzeAgentNode(instance, nodeExec, result));
            }
            completeNodeExecution(nodeExec, outputData);
            advanceWorkflow(instanceId, nodeId, outputData);
        } else {
            nodeExec.setStatus(WorkflowInstanceStatusEnum.FAILED.getCode());
            nodeExec.setErrorMessage("Agent执行失败: " + agentExecutionStatus);
            nodeExec.setCompletedAt(LocalDateTime.now());
            nodeExec.setOutputData(outputData);
            nodeExecutionMapper.updateById(nodeExec);
            failWorkflowInstance(instanceId, nodeId, "Agent执行失败: " + agentExecutionStatus);
            log.error("Agent节点执行失败: instanceId={}, nodeId={}, agentStatus={}", instanceId, nodeId, agentExecutionStatus);
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
        nodeExecutionMapper.updateById(nodeExec);

        log.info("开始执行节点: instanceId={}, nodeId={}, nodeType={}", instance.getId(), nodeId, nodeType);

        try {
            switch (nodeType) {
                case "trigger_manual", "trigger_cron", "trigger_event" ->
                        handleTriggerNode(instance, nodeExec);
                case "agent" ->
                        handleAgentNode(instance, nodeExec, nodeDef);
                case "human_review" ->
                        handleHumanReviewNode(instance, nodeExec, nodeDef);
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
            nodeExec.setStatus(WorkflowInstanceStatusEnum.FAILED.getCode());
            nodeExec.setErrorMessage(e.getMessage());
            nodeExec.setCompletedAt(LocalDateTime.now());
            nodeExecutionMapper.updateById(nodeExec);
            failWorkflowInstance(instance.getId(), nodeId, e.getMessage());
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

    private void handleAgentNode(WorkflowInstance instance, WorkflowNodeExecution nodeExec,
                                  Map<String, Object> nodeDef) {
        Map<String, Object> config = getConfig(nodeDef);
        String agentId = str(config, "agentId");
        String taskInstruction = str(config, "taskInstruction");

        if (!StringUtils.hasText(agentId)) {
            log.warn("Agent节点未配置agentId，使用默认占位执行: nodeId={}", nodeExec.getNodeId());
            Map<String, Object> output = Map.of(
                    "status", WorkflowInstanceStatusEnum.COMPLETED.getCode(),
                    "result", "节点 [" + nodeExec.getNodeLabel() + "] 已执行（未绑定Agent，模拟输出）",
                    "completedAt", LocalDateTime.now().toString());
            completeNodeExecution(nodeExec, output);
            advanceWorkflow(instance.getId(), nodeExec.getNodeId(), output);
            return;
        }

        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new IllegalStateException("Agent不存在: " + agentId);
        }

        String contextStr = contextAssembler.buildAgentContextStr(instance, nodeExec.getInputData());
        String fullInstruction = StringUtils.hasText(taskInstruction)
                ? taskInstruction + "\n\n## 当前流程上下文\n" + contextStr
                : "执行任务：" + nodeExec.getNodeLabel() + "\n\n## 当前流程上下文\n" + contextStr;

        SecurityCheckDecisionVO securityDecision = securityRuntimeGuardServiceProvider.getObject().evaluate(
                buildNodeSecurityCheckRequest(instance, nodeExec, agentId, fullInstruction),
                null
        );
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

        AgentExecutionContext ctx = AgentExecutionContext.builder()
                .executionId(executionId)
                .agentId(agentId)
                .tenantId(tenantId)
                .inputPrompt(fullInstruction)
                .model(agent.getAiModel())
                .agentModelType(agent.getAiModelType())
                .agentModelGroupId(agent.getAiModelGroupId())
                .inputContext(nodeExec.getInputData())
                .maxMessages(18)
                .maxRounds(6)
                .maxToolCallsPerRound(4)
                .build();

        agentExecutionEngineProvider.getObject().execute(ctx)
                .thenAccept(result -> {
                    String callbackStatus = result != null && StringUtils.hasText(result.getStatus())
                            ? result.getStatus() : AgentExecutionStatusEnum.FAILED.getCode();
                    String callbackResult = result != null ? result.getOutputResult() : "Agent执行返回空结果";
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

    private SecurityRuntimeCheckRequest buildNodeSecurityCheckRequest(WorkflowInstance instance,
                                                                      WorkflowNodeExecution nodeExec,
                                                                      String agentId,
                                                                      String content) {
        var request = new SecurityRuntimeCheckRequest();
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
        nodeExecutionMapper.updateById(nodeExec);

        Map<String, Object> config = getConfig(nodeDef);
        List<String> reviewerRoles = (List<String>) config.get("reviewerRoles");
        ApprovalNotificationMessage message = ApprovalNotificationMessage.builder()
                .instanceId(instance.getId())
                .nodeId(nodeExec.getNodeId())
                .reviewerRoles(reviewerRoles)
                .tenantId(instance.getTenantId())
                .build();
        rabbitTemplate.convertAndSend("sf.notification", "", message);

        broadcastWorkflowEvent(instance.getTenantId(), "APPROVAL_REQUEST", Map.of(
                "instanceId", instance.getId(),
                "nodeId", nodeExec.getNodeId(),
                "nodeLabel", nodeExec.getNodeLabel()
        ));

        log.info("人工审核节点已就绪，等待审核操作: instanceId={}, nodeId={}, label={}",
                instance.getId(), nodeExec.getNodeId(), nodeExec.getNodeLabel());
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
        archiveSpecAfterWorkflow(instance);

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

    private void completeNodeExecution(WorkflowNodeExecution nodeExec, Map<String, Object> outputData) {
        nodeExec.setStatus(WorkflowInstanceStatusEnum.COMPLETED.getCode());
        nodeExec.setOutputData(outputData);
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

        broadcastWorkflowEvent(instance.getTenantId(), "WORKFLOW_FAILED", Map.of(
                "instanceId", instanceId,
                "nodeId", nodeId,
                "errorMessage", StringUtils.hasText(errorMessage) ? errorMessage : "unknown error"
        ));
    }

    private void updateCurrentNode(String instanceId, String nodeId) {
        WorkflowInstance update = new WorkflowInstance();
        update.setId(instanceId);
        update.setCurrentNodeId(nodeId);
        update.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(update);
    }

    private void archiveSpecAfterWorkflow(WorkflowInstance instance) {
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
        update.setStatus(SpecStatusEnum.ARCHIVED.getCode());
        update.setUpdatedAt(LocalDateTime.now());
        specMapper.updateById(update);
    }

    private void saveNodeOutput(WorkflowInstance instance, String nodeId, Map<String, Object> outputData) {
        Map<String, Object> variables = instance.getVariables() != null
                ? new HashMap<>(instance.getVariables()) : new HashMap<>();
        variables.put(nodeId + "_output", outputData);

        WorkflowInstance update = new WorkflowInstance();
        update.setId(instance.getId());
        update.setVariables(variables);
        update.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(update);

        instance.setVariables(variables);
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
        Object nodes = definition.get("nodes");
        return nodes instanceof List ? (List<Map<String, Object>>) nodes : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getEdges(Map<String, Object> definition) {
        Object edges = definition.get("edges");
        return edges instanceof List ? (List<Map<String, Object>>) edges : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getConfig(Map<String, Object> nodeDef) {
        Object config = nodeDef.get("config");
        return config instanceof Map ? (Map<String, Object>) config : new HashMap<>();
    }

    private String str(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        return v != null ? String.valueOf(v) : null;
    }
}
