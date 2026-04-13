package com.schemaplexai.service.workflow.engine.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.DeviationSeverityEnum;
import com.schemaplexai.common.enums.DeviationStatusEnum;
import com.schemaplexai.common.enums.NodeTypeEnum;
import com.schemaplexai.dao.mapper.QualityDeviationMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.model.entity.QualityDeviation;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 偏离度分析节点处理器
 *
 * <p>负责执行规则引擎分析、持久化偏离记录、汇总分析结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviationAnalysisHandler {

    private final QualityDeviationMapper qualityDeviationMapper;
    private final WorkflowNodeExecutionMapper nodeExecutionMapper;

    /**
     * 执行偏离分析并返回汇总输出
     */
    public Map<String, Object> analyze(WorkflowInstance instance) {
        String specId = instance.getSpecId();
        Map<String, Object> variables = instance.getVariables();

        List<WorkflowNodeExecution> allExecs = nodeExecutionMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeExecution>()
                        .eq(WorkflowNodeExecution::getInstanceId, instance.getId())
                        .orderByAsc(WorkflowNodeExecution::getCreatedAt)
        );
        List<String> agentOutputs = allExecs.stream()
                .filter(e -> NodeTypeEnum.AGENT.getCode().equals(e.getNodeType())
                        && AgentExecutionStatusEnum.COMPLETED.getCode().equals(e.getStatus()))
                .map(e -> {
                    Map<String, Object> out = e.getOutputData();
                    if (out == null) {
                        return null;
                    }
                    Object tracePayload = out.get("tracePayload");
                    if (tracePayload instanceof Map<?, ?> traceMap) {
                        Object result = traceMap.get("result");
                        return result != null ? String.valueOf(result) : null;
                    }
                    Object handoffPayload = out.get("handoffPayload");
                    if (handoffPayload instanceof Map<?, ?> handoffMap) {
                        Object summary = handoffMap.get("summary");
                        return summary != null ? String.valueOf(summary) : null;
                    }
                    return str(out, "result");
                })
                .filter(s -> s != null && !s.isBlank())
                .toList();

        List<DeviationAnalysisResult> deviations = runAnalysis(specId, agentOutputs, variables);
        persistDeviations(instance, specId, deviations);

        long criticalCount = deviations.stream()
                .filter(d -> DeviationSeverityEnum.CRITICAL.getCode().equals(d.severity())).count();
        long warningCount = deviations.stream()
                .filter(d -> DeviationSeverityEnum.WARNING.getCode().equals(d.severity())).count();
        double score = calculateScore(deviations);

        Map<String, Object> output = new HashMap<>();
        output.put("totalDeviations", deviations.size());
        output.put("criticalCount", criticalCount);
        output.put("warningCount", warningCount);
        output.put("deviationScore", score);
        output.put("deviationLevel", score > 80 ? "LOW" : score > 50 ? "MEDIUM" : "HIGH");
        output.put("analysisCompletedAt", LocalDateTime.now().toString());
        output.put("summary", buildSummary(deviations));

        log.info("偏离分析完成: instanceId={}, total={}, critical={}, score={}",
                instance.getId(), deviations.size(), criticalCount, score);
        return output;
    }

    // -------------------------------------------------------------------------
    //  私有方法
    // -------------------------------------------------------------------------

    private List<DeviationAnalysisResult> runAnalysis(String specId, List<String> agentOutputs,
                                                       Map<String, Object> variables) {
        List<DeviationAnalysisResult> results = new ArrayList<>();

        if (agentOutputs.isEmpty()) {
            results.add(DeviationAnalysisResult.builder()
                    .type("structural")
                    .severity(DeviationSeverityEnum.WARNING.getCode())
                    .title("Agent执行输出为空")
                    .description("工作流中的Agent节点未产生可分析的输出，可能影响交付质量评估")
                    .expectedValue("有效的代码/文档输出")
                    .actualValue("无输出（骨架阶段）")
                    .build());
        }

        if (variables != null) {
            long rejectedCount = variables.entrySet().stream()
                    .filter(e -> e.getKey().contains("_output") && e.getValue() instanceof Map)
                    .filter(e -> CommonConstant.APPROVAL_RESULT_REJECTED.equals(((Map<?, ?>) e.getValue()).get("approvalResult")))
                    .count();
            if (rejectedCount > 0) {
                results.add(DeviationAnalysisResult.builder()
                        .type("semantic")
                        .severity(DeviationSeverityEnum.WARNING.getCode())
                        .title("存在已拒绝的审批节点")
                        .description("工作流中有 " + rejectedCount + " 个审批节点被拒绝过，存在返工记录")
                        .expectedValue("首次审批通过")
                        .actualValue("有审批被拒绝后重做")
                        .build());
            }
        }

        results.add(DeviationAnalysisResult.builder()
                .type("structural")
                .severity(DeviationSeverityEnum.INFO.getCode())
                .title("AI执行处于骨架阶段")
                .description("当前 Agent 执行引擎处于骨架阶段，实际代码产出待 AI Provider 联调后评估")
                .expectedValue("真实 AI 生成的代码/文档")
                .actualValue("骨架占位输出")
                .build());

        return results;
    }

    private void persistDeviations(WorkflowInstance instance, String specId,
                                    List<DeviationAnalysisResult> deviations) {
        for (DeviationAnalysisResult d : deviations) {
            QualityDeviation entity = new QualityDeviation();
            entity.setTenantId(instance.getTenantId());
            entity.setSpecId(specId);
            entity.setDeviationType(d.type());
            entity.setSeverity(d.severity());
            entity.setTitle(d.title());
            entity.setDescription(d.description());
            entity.setExpectedValue(d.expectedValue());
            entity.setActualValue(d.actualValue());
            entity.setStatus(DeviationStatusEnum.OPEN.getCode());
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            qualityDeviationMapper.insert(entity);
        }
    }

    private double calculateScore(List<DeviationAnalysisResult> deviations) {
        if (deviations.isEmpty()) return 100.0;
        double penalty = 0;
        for (DeviationAnalysisResult d : deviations) {
            String severityCode = d.severity();
            if (DeviationSeverityEnum.CRITICAL.getCode().equals(severityCode)) {
                penalty += 30;
            } else if (DeviationSeverityEnum.WARNING.getCode().equals(severityCode)) {
                penalty += 10;
            } else {
                penalty += 2;
            }
        }
        return Math.max(0, 100 - penalty);
    }

    private String buildSummary(List<DeviationAnalysisResult> deviations) {
        if (deviations.isEmpty()) return "未发现明显偏离，交付质量良好";
        StringBuilder sb = new StringBuilder();
        sb.append("偏离分析发现 ").append(deviations.size()).append(" 项问题：\n");
        for (DeviationAnalysisResult d : deviations) {
            sb.append(String.format("- [%s] %s: %s\n",
                    d.severity().toUpperCase(), d.title(), d.description()));
        }
        return sb.toString();
    }

    private String str(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        return v != null ? String.valueOf(v) : null;
    }
}
