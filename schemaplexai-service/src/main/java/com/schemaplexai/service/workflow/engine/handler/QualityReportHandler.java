package com.schemaplexai.service.workflow.engine.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.DeviationSeverityEnum;
import com.schemaplexai.common.enums.NodeTypeEnum;
import com.schemaplexai.dao.mapper.QualityDeviationMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.model.entity.QualityDeviation;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 质量报告节点处理器
 *
 * <p>汇总整个工作流执行情况，生成质量保障报告。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QualityReportHandler {

    private final WorkflowNodeExecutionMapper nodeExecutionMapper;
    private final QualityDeviationMapper qualityDeviationMapper;

    /**
     * 生成质量保障报告
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> generateReport(WorkflowInstance instance) {
        List<WorkflowNodeExecution> allExecs = nodeExecutionMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeExecution>()
                        .eq(WorkflowNodeExecution::getInstanceId, instance.getId())
                        .orderByAsc(WorkflowNodeExecution::getCreatedAt)
        );

        long totalNodes = allExecs.size();
        long completedNodes = allExecs.stream()
                .filter(e -> AgentExecutionStatusEnum.COMPLETED.getCode().equals(e.getStatus())).count();
        long failedNodes = allExecs.stream()
                .filter(e -> AgentExecutionStatusEnum.FAILED.getCode().equals(e.getStatus())).count();

        long deviationCount = 0;
        long criticalDeviations = 0;
        if (StringUtils.hasText(instance.getSpecId())) {
            List<QualityDeviation> deviations = qualityDeviationMapper.selectList(
                    new LambdaQueryWrapper<QualityDeviation>()
                            .eq(QualityDeviation::getSpecId, instance.getSpecId()));
            deviationCount = deviations.size();
            criticalDeviations = deviations.stream()
                    .filter(d -> DeviationSeverityEnum.CRITICAL.getCode().equals(d.getSeverity())).count();
        }

        double deviationScore = resolveDeviationScore(allExecs);

        List<Map<String, Object>> agentSummaries = allExecs.stream()
                .filter(e -> NodeTypeEnum.AGENT.getCode().equals(e.getNodeType()))
                .map(e -> {
                    Map<String, Object> summary = new HashMap<>();
                    summary.put("nodeLabel", e.getNodeLabel());
                    summary.put("status", e.getStatus());
                    summary.put("startedAt", e.getStartedAt() != null ? e.getStartedAt().toString() : null);
                    summary.put("completedAt", e.getCompletedAt() != null ? e.getCompletedAt().toString() : null);
                    if (e.getOutputData() != null) {
                        Object tracePayload = e.getOutputData().get("tracePayload");
                        Object handoffPayload = e.getOutputData().get("handoffPayload");
                        if (tracePayload instanceof Map<?, ?> traceMap) {
                            Object result = traceMap.get("result");
                            summary.put("result", result);
                            summary.put("qualitySummary", traceMap.get("qualitySummary"));
                            summary.put("qualityTaskId", traceMap.get("qualityTaskId"));
                        }
                        if (handoffPayload instanceof Map<?, ?> handoffMap && summary.get("result") == null) {
                            summary.put("result", handoffMap.get("summary"));
                            summary.put("qualitySummary", handoffMap.get("qualitySummary"));
                            summary.put("qualityTaskId", handoffMap.get("qualityTaskId"));
                        }
                    }
                    return summary;
                })
                .toList();

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

    // -------------------------------------------------------------------------
    //  私有方法
    // -------------------------------------------------------------------------

    private double resolveDeviationScore(List<WorkflowNodeExecution> allExecs) {
        Optional<WorkflowNodeExecution> deviationExec = allExecs.stream()
                .filter(e -> "deviation_analysis".equals(e.getNodeType())
                        && AgentExecutionStatusEnum.COMPLETED.getCode().equals(e.getStatus()))
                .findFirst();
        if (deviationExec.isPresent() && deviationExec.get().getOutputData() != null) {
            Object score = deviationExec.get().getOutputData().get("deviationScore");
            if (score instanceof Number num) {
                return num.doubleValue();
            }
        }
        return 85.0;
    }

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
}
