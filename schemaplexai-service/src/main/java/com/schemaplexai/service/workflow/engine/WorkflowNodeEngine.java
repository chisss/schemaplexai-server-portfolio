package com.schemaplexai.service.workflow.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.QualityDeviationMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.QualityDeviation;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionEngine;
import com.schemaplexai.service.mq.AgentContextPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 工作流节点驱动引擎
 *
 * <p>负责解析工作流定义（definition JSONB），按节点类型驱动各步骤：
 * <ul>
 *   <li>trigger/start → 自动完成，推进到下一节点</li>
 *   <li>agent → 触发 AgentExecutionEngine 异步执行，完成后继续推进</li>
 *   <li>human_review → 置为 pending，等待人工 approve/reject</li>
 *   <li>deviation_analysis → 执行偏离分析，输出报告，推进</li>
 *   <li>quality_report → 生成质量保障报告，推进</li>
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
    private final QualityDeviationMapper qualityDeviationMapper;
    private final AgentExecutionEngine agentExecutionEngine;
    private final AgentContextPublisher agentContextPublisher;

    /**
     * 自注入自身代理，用于让 @Async 注解在同类方法调用时生效（绕过 Spring AOP 自调用限制）
     */
    @Lazy
    private WorkflowNodeEngine self;

    // =====================================================================
    //  公开入口
    // =====================================================================

    /**
     * 工作流启动时初始化所有节点执行记录，并驱动第一个节点
     *
     * @param instance 工作流实例（status 已设置为 running）
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
            completeWorkflowInstance(instance, "completed");
            return;
        }

        // 创建所有节点的执行记录（pending 状态）
        for (Map<String, Object> node : nodes) {
            String nodeId = str(node, "id");
            String nodeType = str(node, "type");
            String nodeLabel = str(node, "label");

            // 检查是否已存在（防止重复初始化）
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
                exec.setStatus("pending");
                exec.setInputData(new HashMap<>());
                exec.setOutputData(new HashMap<>());
                nodeExecutionMapper.insert(exec);
            }
        }

        // 找到起始节点（trigger 类型 或 第一个节点）
        String startNodeId = findStartNodeId(nodes);
        if (!StringUtils.hasText(startNodeId)) {
            log.warn("未找到起始节点: instanceId={}", instance.getId());
            return;
        }

        // 更新实例当前节点
        updateCurrentNode(instance.getId(), startNodeId);

        // 驱动起始节点（通过代理调用，确保 @Async 生效）
        self.driveNode(instance, startNodeId, nodes, edges, new HashMap<>());
    }

    /**
     * 某节点完成后，推进到下一个节点（approve 操作调用此方法）
     *
     * @param instanceId       工作流实例 ID
     * @param completedNodeId  刚完成的节点 ID
     * @param outputData       该节点的输出数据
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

        // 将输出数据保存到实例变量（供后续节点使用）
        saveNodeOutput(instance, completedNodeId, outputData);

        // 找到当前节点的后继节点列表
        List<String> nextNodeIds = findNextNodes(completedNodeId, edges);
        if (nextNodeIds.isEmpty()) {
            log.info("节点 {} 没有后继节点，工作流自然结束: instanceId={}", completedNodeId, instanceId);
            completeWorkflowInstance(instance, "completed");
            return;
        }

        // 推进到下一节点（通过代理调用，确保 @Async 生效；当前只支持单路径，并行扩展预留）
        for (String nextNodeId : nextNodeIds) {
            updateCurrentNode(instanceId, nextNodeId);
            self.driveNode(instance, nextNodeId, nodes, edges, outputData);
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
        outputData.put("agentResult", result);
        outputData.put("completedAt", LocalDateTime.now().toString());

        if ("completed".equals(agentExecutionStatus) || "stopped".equals(agentExecutionStatus)) {
            completeNodeExecution(nodeExec, outputData);
            advanceWorkflow(instanceId, nodeId, outputData);
        } else {
            // agent 执行失败，节点标为 failed
            nodeExec.setStatus("failed");
            nodeExec.setErrorMessage("Agent执行失败: " + agentExecutionStatus);
            nodeExec.setCompletedAt(LocalDateTime.now());
            nodeExec.setOutputData(outputData);
            nodeExecutionMapper.updateById(nodeExec);
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

        // 设置输入数据（上一节点的输出 + 实例变量）
        Map<String, Object> inputData = buildInputData(instance, previousOutput);
        nodeExec.setInputData(inputData);
        nodeExec.setStatus("running");
        nodeExec.setStartedAt(LocalDateTime.now());
        nodeExecutionMapper.updateById(nodeExec);

        log.info("开始执行节点: instanceId={}, nodeId={}, nodeType={}", instance.getId(), nodeId, nodeType);

        try {
            switch (nodeType) {
                case "trigger_manual", "trigger_cron", "trigger_event" -> handleTriggerNode(instance, nodeExec, nodeDef, nodes, edges);
                case "agent" -> handleAgentNode(instance, nodeExec, nodeDef);
                case "human_review" -> handleHumanReviewNode(instance, nodeExec, nodeDef);
                case "deviation_analysis" -> handleDeviationAnalysisNode(instance, nodeExec, nodeDef, nodes, edges);
                case "quality_report" -> handleQualityReportNode(instance, nodeExec, nodeDef, nodes, edges);
                case "end" -> handleEndNode(instance, nodeExec);
                default -> {
                    log.info("未特殊处理的节点类型，自动完成: nodeType={}", nodeType);
                    completeNodeExecution(nodeExec, Map.of("auto", true));
                    advanceWorkflow(instance.getId(), nodeId, Map.of("auto", true));
                }
            }
        } catch (Exception e) {
            log.error("节点执行异常: instanceId={}, nodeId={}, nodeType={}", instance.getId(), nodeId, nodeType, e);
            nodeExec.setStatus("failed");
            nodeExec.setErrorMessage(e.getMessage());
            nodeExec.setCompletedAt(LocalDateTime.now());
            nodeExecutionMapper.updateById(nodeExec);
        }
    }

    // =====================================================================
    //  各节点类型处理
    // =====================================================================

    /**
     * 触发节点 — 自动完成，推进到下一节点
     */
    private void handleTriggerNode(WorkflowInstance instance, WorkflowNodeExecution nodeExec,
                                    Map<String, Object> nodeDef,
                                    List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, Object> output = Map.of("triggered", true, "triggeredAt", LocalDateTime.now().toString());
        completeNodeExecution(nodeExec, output);
        advanceWorkflow(instance.getId(), nodeExec.getNodeId(), output);
    }

    /**
     * Agent 节点 — 触发 AgentExecutionEngine 异步执行
     */
    private void handleAgentNode(WorkflowInstance instance, WorkflowNodeExecution nodeExec,
                                  Map<String, Object> nodeDef) {
        Map<String, Object> config = getConfig(nodeDef);
        String agentId = str(config, "agentId");
        String taskInstruction = str(config, "taskInstruction");

        if (!StringUtils.hasText(agentId)) {
            log.warn("Agent节点未配置agentId，使用默认占位执行: nodeId={}", nodeExec.getNodeId());
            // 无 agentId 时，模拟执行完成（防止流程卡死）
            Map<String, Object> output = Map.of(
                    "status", "completed",
                    "result", "节点 [" + nodeExec.getNodeLabel() + "] 已执行（未绑定Agent，模拟输出）",
                    "completedAt", LocalDateTime.now().toString());
            completeNodeExecution(nodeExec, output);
            advanceWorkflow(instance.getId(), nodeExec.getNodeId(), output);
            return;
        }

        // 构建 Agent 执行上下文（包含上一节点输出作为任务上下文）
        String contextStr = buildAgentContextStr(instance, nodeExec.getInputData());
        String fullInstruction = StringUtils.hasText(taskInstruction)
                ? taskInstruction + "\n\n## 当前流程上下文\n" + contextStr
                : "执行任务：" + nodeExec.getNodeLabel() + "\n\n## 当前流程上下文\n" + contextStr;

        // 创建 AgentExecution 记录
        AgentExecution agentExecution = new AgentExecution();
        agentExecution.setAgentId(agentId);
        agentExecution.setTenantId(instance.getTenantId());
        agentExecution.setInputPrompt(fullInstruction);
        agentExecution.setStatus("pending");
        agentExecution.setCreatedAt(LocalDateTime.now());
        agentExecutionMapper.insert(agentExecution);

        // 更新节点执行记录，关联 agentExecutionId
        nodeExec.setAgentExecutionId(agentExecution.getId());
        nodeExecutionMapper.updateById(nodeExec);

        String instanceId = instance.getId();
        String nodeId = nodeExec.getNodeId();
        String nodeLabel = nodeExec.getNodeLabel();
        String tenantId = instance.getTenantId();
        String executionId = agentExecution.getId();
        // teamAgentId 来自节点配置（Team Agent 场景），Solo Agent 场景以 instanceId 代替
        String teamAgentId = StringUtils.hasText(str(config, "teamAgentId"))
                ? str(config, "teamAgentId") : instanceId;

        // 异步触发执行，完成后回调 onAgentNodeCompleted
        AgentExecutionContext ctx = AgentExecutionContext.builder()
                .executionId(executionId)
                .agentId(agentId)
                .tenantId(tenantId)
                .inputPrompt(fullInstruction)
                .build();

        agentExecutionEngine.execute(ctx).thenAccept(result -> {
            onAgentNodeCompleted(instanceId, nodeId, result.getStatus(), result.getOutputResult());
            // 发布 MQ 上下文共享消息，供 Team Agent 中其他 Sub-Agent 感知产出
            agentContextPublisher.publishAgentOutput(
                    teamAgentId, agentId, executionId, instanceId,
                    nodeId, nodeLabel, result.getStatus(), result.getOutputResult(), tenantId);
        });

        log.info("Agent节点已触发异步执行: instanceId={}, nodeId={}, agentId={}, executionId={}",
                instance.getId(), nodeId, agentId, executionId);
    }

    /**
     * 人工审核节点 — 置为 pending，等待人工 approve/reject API 调用
     */
    private void handleHumanReviewNode(WorkflowInstance instance, WorkflowNodeExecution nodeExec,
                                        Map<String, Object> nodeDef) {
        // 将节点状态改为 pending，等待人工操作
        nodeExec.setStatus("pending");
        nodeExecutionMapper.updateById(nodeExec);

        log.info("人工审核节点已就绪，等待审核操作: instanceId={}, nodeId={}, label={}",
                instance.getId(), nodeExec.getNodeId(), nodeExec.getNodeLabel());
    }

    /**
     * 偏离度分析节点 — 分析 Spec 与 Agent 执行产出的偏差，生成偏离记录
     */
    private void handleDeviationAnalysisNode(WorkflowInstance instance, WorkflowNodeExecution nodeExec,
                                              Map<String, Object> nodeDef,
                                              List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        String specId = instance.getSpecId();
        Map<String, Object> variables = instance.getVariables();

        // 收集工作流中所有 agent 节点的输出作为分析材料
        List<WorkflowNodeExecution> allExecs = nodeExecutionMapper.selectByInstanceId(instance.getId());
        List<String> agentOutputs = allExecs.stream()
                .filter(e -> "agent".equals(e.getNodeType()) && "completed".equals(e.getStatus()))
                .map(e -> {
                    Map<String, Object> out = e.getOutputData();
                    return out != null ? str(out, "result") : null;
                })
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());

        // 执行模拟偏离分析（骨架实现）
        List<DeviationAnalysisResult> deviations = runDeviationAnalysis(specId, agentOutputs, variables);

        // 将偏离分析结果持久化
        for (DeviationAnalysisResult deviation : deviations) {
            QualityDeviation entity = new QualityDeviation();
            entity.setTenantId(instance.getTenantId());
            entity.setSpecId(specId);
            entity.setDeviationType(deviation.getType());
            entity.setSeverity(deviation.getSeverity());
            entity.setTitle(deviation.getTitle());
            entity.setDescription(deviation.getDescription());
            entity.setExpectedValue(deviation.getExpectedValue());
            entity.setActualValue(deviation.getActualValue());
            entity.setStatus("open");
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            qualityDeviationMapper.insert(entity);
        }

        // 汇总偏离分析结果
        long criticalCount = deviations.stream().filter(d -> "critical".equals(d.getSeverity())).count();
        long warningCount = deviations.stream().filter(d -> "warning".equals(d.getSeverity())).count();
        double deviationScore = calculateDeviationScore(deviations);

        Map<String, Object> output = new HashMap<>();
        output.put("totalDeviations", deviations.size());
        output.put("criticalCount", criticalCount);
        output.put("warningCount", warningCount);
        output.put("deviationScore", deviationScore);
        output.put("deviationLevel", deviationScore > 80 ? "LOW" : deviationScore > 50 ? "MEDIUM" : "HIGH");
        output.put("analysisCompletedAt", LocalDateTime.now().toString());
        output.put("summary", buildDeviationSummary(deviations));

        log.info("偏离分析完成: instanceId={}, total={}, critical={}, score={}",
                instance.getId(), deviations.size(), criticalCount, deviationScore);

        completeNodeExecution(nodeExec, output);
        advanceWorkflow(instance.getId(), nodeExec.getNodeId(), output);
    }

    /**
     * 质量报告节点 — 汇总整个工作流执行情况，生成质量保障报告
     */
    private void handleQualityReportNode(WorkflowInstance instance, WorkflowNodeExecution nodeExec,
                                          Map<String, Object> nodeDef,
                                          List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, Object> report = generateQualityReport(instance);
        Map<String, Object> output = new HashMap<>();
        output.put("qualityReport", report);
        output.put("reportGeneratedAt", LocalDateTime.now().toString());

        log.info("质量保障报告已生成: instanceId={}", instance.getId());
        completeNodeExecution(nodeExec, output);
        advanceWorkflow(instance.getId(), nodeExec.getNodeId(), output);
    }

    /**
     * 结束节点 — 完成工作流实例，如无质量报告节点则在此生成报告
     */
    private void handleEndNode(WorkflowInstance instance, WorkflowNodeExecution nodeExec) {
        // 检查是否已有质量报告（若工作流中有 quality_report 节点则已生成）
        List<WorkflowNodeExecution> allExecs = nodeExecutionMapper.selectByInstanceId(instance.getId());
        boolean hasQualityReport = allExecs.stream()
                .anyMatch(e -> "quality_report".equals(e.getNodeType()) && "completed".equals(e.getStatus()));

        Map<String, Object> output = new HashMap<>();
        if (!hasQualityReport) {
            // 在结束节点生成质量报告
            output.put("qualityReport", generateQualityReport(instance));
        }
        output.put("workflowCompleted", true);
        output.put("completedAt", LocalDateTime.now().toString());

        completeNodeExecution(nodeExec, output);

        // 保存最终报告到实例变量
        saveNodeOutput(instance, nodeExec.getNodeId(), output);

        // 完成工作流实例
        completeWorkflowInstance(instance, "completed");

        log.info("工作流已完成: instanceId={}", instance.getId());
    }

    // =====================================================================
    //  偏离分析逻辑
    // =====================================================================

    /**
     * 执行偏离度分析（当前为规则引擎骨架，可替换为 AI 分析）
     */
    private List<DeviationAnalysisResult> runDeviationAnalysis(String specId,
                                                                 List<String> agentOutputs,
                                                                 Map<String, Object> variables) {
        List<DeviationAnalysisResult> results = new ArrayList<>();

        // 检查是否有 agent 输出（如果没有，说明开发任务可能未完成）
        if (agentOutputs.isEmpty()) {
            results.add(DeviationAnalysisResult.builder()
                    .type("structural")
                    .severity("warning")
                    .title("Agent执行输出为空")
                    .description("工作流中的Agent节点未产生可分析的输出，可能影响交付质量评估")
                    .expectedValue("有效的代码/文档输出")
                    .actualValue("无输出（骨架阶段）")
                    .build());
        }

        // 检查实例变量中是否记录了审批意见（拒绝意见代表存在偏离风险）
        if (variables != null) {
            long rejectedCount = variables.entrySet().stream()
                    .filter(e -> e.getKey().contains("_output") && e.getValue() instanceof Map)
                    .filter(e -> "rejected".equals(((Map<?, ?>) e.getValue()).get("approvalResult")))
                    .count();
            if (rejectedCount > 0) {
                results.add(DeviationAnalysisResult.builder()
                        .type("semantic")
                        .severity("warning")
                        .title("存在已拒绝的审批节点")
                        .description("工作流中有 " + rejectedCount + " 个审批节点被拒绝过，存在返工记录")
                        .expectedValue("首次审批通过")
                        .actualValue("有审批被拒绝后重做")
                        .build());
            }
        }

        // 当前 Agent 执行骨架阶段均为模拟输出，输出 info 级别偏离
        results.add(DeviationAnalysisResult.builder()
                .type("structural")
                .severity("info")
                .title("AI执行处于骨架阶段")
                .description("当前 Agent 执行引擎处于骨架阶段，实际代码产出待 AI Provider 联调后评估")
                .expectedValue("真实 AI 生成的代码/文档")
                .actualValue("骨架占位输出")
                .build());

        return results;
    }

    /**
     * 计算偏离评分（0-100，越高表示偏离越小/质量越好）
     */
    private double calculateDeviationScore(List<DeviationAnalysisResult> deviations) {
        if (deviations.isEmpty()) return 100.0;
        double penalty = 0;
        for (DeviationAnalysisResult d : deviations) {
            switch (d.getSeverity()) {
                case "critical" -> penalty += 30;
                case "warning" -> penalty += 10;
                case "info" -> penalty += 2;
            }
        }
        return Math.max(0, 100 - penalty);
    }

    /**
     * 构建偏离摘要文本
     */
    private String buildDeviationSummary(List<DeviationAnalysisResult> deviations) {
        if (deviations.isEmpty()) return "未发现明显偏离，交付质量良好";
        StringBuilder sb = new StringBuilder();
        sb.append("偏离分析发现 ").append(deviations.size()).append(" 项问题：\n");
        for (DeviationAnalysisResult d : deviations) {
            sb.append(String.format("- [%s] %s: %s\n",
                    d.getSeverity().toUpperCase(), d.getTitle(), d.getDescription()));
        }
        return sb.toString();
    }

    // =====================================================================
    //  质量报告生成
    // =====================================================================

    /**
     * 生成质量保障报告
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> generateQualityReport(WorkflowInstance instance) {
        List<WorkflowNodeExecution> allExecs = nodeExecutionMapper.selectByInstanceId(instance.getId());

        // 统计节点执行情况
        long totalNodes = allExecs.size();
        long completedNodes = allExecs.stream().filter(e -> "completed".equals(e.getStatus())).count();
        long failedNodes = allExecs.stream().filter(e -> "failed".equals(e.getStatus())).count();

        // 统计偏离情况
        long deviationCount = 0;
        long criticalDeviations = 0;
        if (StringUtils.hasText(instance.getSpecId())) {
            List<QualityDeviation> deviations = qualityDeviationMapper.selectList(
                    new LambdaQueryWrapper<QualityDeviation>()
                            .eq(QualityDeviation::getSpecId, instance.getSpecId()));
            deviationCount = deviations.size();
            criticalDeviations = deviations.stream()
                    .filter(d -> "critical".equals(d.getSeverity())).count();
        }

        // 从偏离分析节点获取评分
        double deviationScore = 85.0; // 默认分数
        Optional<WorkflowNodeExecution> deviationExec = allExecs.stream()
                .filter(e -> "deviation_analysis".equals(e.getNodeType()) && "completed".equals(e.getStatus()))
                .findFirst();
        if (deviationExec.isPresent() && deviationExec.get().getOutputData() != null) {
            Object score = deviationExec.get().getOutputData().get("deviationScore");
            if (score instanceof Number) {
                deviationScore = ((Number) score).doubleValue();
            }
        }

        // 收集所有 agent 执行结果摘要
        List<Map<String, Object>> agentSummaries = allExecs.stream()
                .filter(e -> "agent".equals(e.getNodeType()))
                .map(e -> {
                    Map<String, Object> summary = new HashMap<>();
                    summary.put("nodeLabel", e.getNodeLabel());
                    summary.put("status", e.getStatus());
                    summary.put("startedAt", e.getStartedAt() != null ? e.getStartedAt().toString() : null);
                    summary.put("completedAt", e.getCompletedAt() != null ? e.getCompletedAt().toString() : null);
                    if (e.getOutputData() != null) {
                        summary.put("result", e.getOutputData().get("result"));
                    }
                    return summary;
                })
                .collect(Collectors.toList());

        // 构建质量报告
        Map<String, Object> report = new HashMap<>();
        report.put("instanceId", instance.getId());
        report.put("specId", instance.getSpecId());
        report.put("workflowName", instance.getName());
        report.put("totalNodes", totalNodes);
        report.put("completedNodes", completedNodes);
        report.put("failedNodes", failedNodes);
        report.put("completionRate", totalNodes > 0 ? (double) completedNodes / totalNodes * 100 : 0);
        report.put("deviationCount", deviationCount);
        report.put("criticalDeviations", criticalDeviations);
        report.put("qualityScore", deviationScore);
        report.put("overallStatus", criticalDeviations == 0 && failedNodes == 0 ? "PASS" : "REVIEW_NEEDED");
        report.put("agentExecutionSummaries", agentSummaries);
        report.put("reportGeneratedAt", LocalDateTime.now().toString());
        report.put("recommendation", buildRecommendation(criticalDeviations, failedNodes, deviationScore));

        return report;
    }

    /**
     * 根据质量指标生成建议
     */
    private String buildRecommendation(long criticalDeviations, long failedNodes, double score) {
        if (criticalDeviations > 0) {
            return "存在 " + criticalDeviations + " 项严重偏离，建议暂停交付，优先修复关键问题后重新评审";
        }
        if (failedNodes > 0) {
            return "存在 " + failedNodes + " 个执行失败的节点，建议检查失败原因并重新执行相关任务";
        }
        if (score >= 80) {
            return "质量评分良好（" + String.format("%.1f", score) + "分），可以推进交付流程";
        }
        return "质量评分偏低（" + String.format("%.1f", score) + "分），建议进行额外的质量评审";
    }

    // =====================================================================
    //  工具方法
    // =====================================================================

    /**
     * 查找工作流的起始节点（trigger 类型 或 排序第一个节点）
     */
    private String findStartNodeId(List<Map<String, Object>> nodes) {
        // 优先找 trigger 类型的节点
        return nodes.stream()
                .filter(n -> {
                    String type = str(n, "type");
                    return type != null && (type.startsWith("trigger") || "start".equals(type));
                })
                .map(n -> str(n, "id"))
                .findFirst()
                .orElseGet(() -> nodes.isEmpty() ? null : str(nodes.get(0), "id"));
    }

    /**
     * 根据边定义找到当前节点的后继节点列表
     */
    private List<String> findNextNodes(String currentNodeId, List<Map<String, Object>> edges) {
        return edges.stream()
                .filter(e -> currentNodeId.equals(str(e, "source")))
                .map(e -> str(e, "target"))
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
    }

    /**
     * 根据 ID 找到节点定义
     */
    private Map<String, Object> findNodeDef(String nodeId, List<Map<String, Object>> nodes) {
        return nodes.stream()
                .filter(n -> nodeId.equals(str(n, "id")))
                .findFirst()
                .orElse(null);
    }

    /**
     * 根据 instanceId + nodeId 找到节点执行记录
     */
    private WorkflowNodeExecution findNodeExecution(String instanceId, String nodeId) {
        List<WorkflowNodeExecution> execs = nodeExecutionMapper.selectByInstanceId(instanceId);
        return execs.stream()
                .filter(e -> nodeId.equals(e.getNodeId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 完成节点执行记录
     */
    private void completeNodeExecution(WorkflowNodeExecution nodeExec, Map<String, Object> outputData) {
        nodeExec.setStatus("completed");
        nodeExec.setOutputData(outputData);
        nodeExec.setCompletedAt(LocalDateTime.now());
        nodeExecutionMapper.updateById(nodeExec);
    }

    /**
     * 完成工作流实例
     */
    private void completeWorkflowInstance(WorkflowInstance instance, String status) {
        instance.setStatus(status);
        instance.setCompletedAt(LocalDateTime.now());
        instance.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(instance);
    }

    /**
     * 更新实例当前执行节点
     */
    private void updateCurrentNode(String instanceId, String nodeId) {
        WorkflowInstance update = new WorkflowInstance();
        update.setId(instanceId);
        update.setCurrentNodeId(nodeId);
        update.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(update);
    }

    /**
     * 将节点输出保存到实例变量
     */
    private void saveNodeOutput(WorkflowInstance instance, String nodeId, Map<String, Object> outputData) {
        Map<String, Object> variables = instance.getVariables() != null
                ? new HashMap<>(instance.getVariables()) : new HashMap<>();
        variables.put(nodeId + "_output", outputData);

        WorkflowInstance update = new WorkflowInstance();
        update.setId(instance.getId());
        update.setVariables(variables);
        update.setUpdatedAt(LocalDateTime.now());
        instanceMapper.updateById(update);

        // 更新内存中的变量，方便后续使用
        instance.setVariables(variables);
    }

    /**
     * 构建节点输入数据（合并上一节点输出 + 实例变量摘要）
     */
    private Map<String, Object> buildInputData(WorkflowInstance instance, Map<String, Object> previousOutput) {
        Map<String, Object> input = new HashMap<>();
        if (previousOutput != null) {
            input.putAll(previousOutput);
        }
        if (instance.getVariables() != null) {
            input.put("_instanceVariables", instance.getVariables());
        }
        input.put("_specId", instance.getSpecId());
        input.put("_instanceId", instance.getId());
        return input;
    }

    /**
     * 构建供 Agent 使用的上下文字符串（防止上下文爆炸：只传关键摘要）
     */
    private String buildAgentContextStr(WorkflowInstance instance, Map<String, Object> inputData) {
        StringBuilder sb = new StringBuilder();
        sb.append("工作流实例: ").append(instance.getName()).append("\n");
        if (StringUtils.hasText(instance.getSpecId())) {
            sb.append("关联Spec ID: ").append(instance.getSpecId()).append("\n");
        }

        // 只传入最近一个节点的输出摘要（防止上下文爆炸）
        if (inputData != null && !inputData.isEmpty()) {
            sb.append("上游节点输出摘要:\n");
            inputData.entrySet().stream()
                    .filter(e -> !e.getKey().startsWith("_"))
                    .limit(5) // 最多5个关键字段
                    .forEach(e -> sb.append("  ").append(e.getKey()).append(": ")
                            .append(truncate(String.valueOf(e.getValue()), 200))
                            .append("\n"));
        }
        return sb.toString();
    }

    /**
     * 从 definition 中提取 nodes 列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getNodes(Map<String, Object> definition) {
        Object nodes = definition.get("nodes");
        if (nodes instanceof List) {
            return (List<Map<String, Object>>) nodes;
        }
        return new ArrayList<>();
    }

    /**
     * 从 definition 中提取 edges 列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getEdges(Map<String, Object> definition) {
        Object edges = definition.get("edges");
        if (edges instanceof List) {
            return (List<Map<String, Object>>) edges;
        }
        return new ArrayList<>();
    }

    /**
     * 从节点定义中获取 config 对象
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getConfig(Map<String, Object> nodeDef) {
        Object config = nodeDef.get("config");
        if (config instanceof Map) {
            return (Map<String, Object>) config;
        }
        return new HashMap<>();
    }

    /**
     * 安全获取 Map 中的字符串值
     */
    private String str(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    /**
     * 截断字符串（防止上下文爆炸）
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    // =====================================================================
    //  内部 DTO
    // =====================================================================

    @lombok.Data
    @lombok.Builder
    static class DeviationAnalysisResult {
        private String type;
        private String severity;
        private String title;
        private String description;
        private String expectedValue;
        private String actualValue;
    }
}
