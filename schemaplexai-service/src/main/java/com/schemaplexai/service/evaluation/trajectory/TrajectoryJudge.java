package com.schemaplexai.service.evaluation.trajectory;

import com.schemaplexai.service.quality.strategy.ModelBasedQualityEvaluator;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Agent 轨迹 Judge（LLM-as-Judge 评估轨迹模式专用）
 * <p>
 * 复用质量中心 {@link ModelBasedQualityEvaluator} 的模型调用 + JSON 结构化解析/修复能力，
 * 以轨迹五维 rubric 对完整执行轨迹评审，双模型并行 + 简版共识合并：
 * </p>
 * <ul>
 *   <li>task_completion 任务完成度：最终回答是否解决用户问题、与基线是否一致</li>
 *   <li>tool_selection 工具选择：工具是否恰当、入参是否合理、是否存在捏造工具</li>
 *   <li>multi_turn_planning 多轮规划：轮次是否高效、是否存在多余调用或循环</li>
 *   <li>boundary_handling 边界容错：超限/循环/降级/暂停/失败时的表现</li>
 *   <li>hallucination 幻觉：虚构事实、捏造工具或杜撰工具返回</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrajectoryJudge {

    /** 单模型评审超时（秒），与质量中心交叉审查口径一致 */
    private static final long JUDGE_TIMEOUT_SECONDS = 90;

    /** 轨迹五维评审系统提示词 */
    private static final String TRAJECTORY_JUDGE_SYSTEM_PROMPT = """
            你是 SchemaPlexAI 的 Agent 轨迹评估器。
            请基于用户问题、参考基线与完整执行轨迹，对 Agent 表现按五个维度独立评审。

            维度定义：
            1. task_completion 任务完成度：最终回答是否解决用户问题，与参考基线是否一致
            2. tool_selection 工具选择：调用的工具是否恰当、入参是否合理、是否存在捏造不存在的工具
            3. multi_turn_planning 多轮规划：执行轮次是否高效，是否存在多余调用、重复调用或循环
            4. boundary_handling 边界容错：工具调用超限、循环告警、降级收敛、暂停等待审批、执行失败时的表现是否合理
            5. hallucination 幻觉：是否存在虚构事实、捏造工具返回或杜撰数据

            评审要求：
            1. 只输出高置信度问题，不为凑数输出含糊意见
            2. 问题描述必须指向轨迹中的具体片段（消息编号/轮次/事件）
            3. 若 hasIssue=true 则 findings 至少 1 条，summary 中提到的关键问题必须在 findings 中逐条体现
            4. 只有在确认无高置信度问题时，才允许 hasIssue=false、findings=[]、score=90-100

            请严格按照以下 JSON 格式返回，不要添加任何额外说明：
            {
              "hasIssue": true/false,
              "score": 0-100,
              "summary": "一句话总结",
              "findings": [
                {
                  "type": "task_completion|tool_selection|multi_turn_planning|boundary_handling|hallucination",
                  "severity": "critical|warning|info",
                  "confidence": 0-100,
                  "title": "问题标题",
                  "description": "问题描述",
                  "location": "轨迹中的位置（消息编号/轮次）",
                  "suggestion": "改进建议"
                }
              ]
            }
            """;

    private final ModelBasedQualityEvaluator modelEvaluator;

    /**
     * 对轨迹执行双模型 Judge 评审
     *
     * @param judgeModelIds Judge 模型 ID 列表（取前 2 个，至少 1 个）
     * @param userQuestion  数据集条目的用户问题
     * @param expectedBaseline 参考基线/期望输出（可空）
     * @param trajectoryText   轨迹评审文本（{@link TrajectoryCollector#renderForJudge}）
     */
    public JudgeOutcome judge(List<String> judgeModelIds, String userQuestion,
                              String expectedBaseline, String trajectoryText) {
        List<String> normalized = normalizeModelIds(judgeModelIds);
        if (normalized.isEmpty()) {
            return JudgeOutcome.builder()
                    .score(0)
                    .hasIssue(true)
                    .summary("未配置 Judge 模型，无法评审")
                    .findings(List.of())
                    .modelVerdicts(List.of())
                    .build();
        }
        String userPrompt = buildUserPrompt(userQuestion, expectedBaseline, trajectoryText);

        List<CompletableFuture<ModelVerdict>> futures = normalized.stream()
                .map(modelId -> CompletableFuture
                        .supplyAsync(() -> evaluateSingle(modelId, userPrompt))
                        .completeOnTimeout(
                                ModelVerdict.timedOut(modelId),
                                JUDGE_TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        )
                        .exceptionally(ex -> ModelVerdict.failed(modelId, ex)))
                .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        List<ModelVerdict> verdicts = futures.stream().map(CompletableFuture::join).toList();

        return merge(verdicts);
    }

    private ModelVerdict evaluateSingle(String modelId, String userPrompt) {
        QualityEvaluationStrategy.EvaluationResult result =
                modelEvaluator.evaluate(modelId, TRAJECTORY_JUDGE_SYSTEM_PROMPT, userPrompt);
        return ModelVerdict.builder()
                .modelId(modelId)
                .hasIssue(result.hasIssue())
                .score(result.score())
                .summary(result.summary())
                .findings(result.findings())
                .timedOut(false)
                .build();
    }

    private String buildUserPrompt(String userQuestion, String expectedBaseline, String trajectoryText) {
        StringBuilder builder = new StringBuilder();
        builder.append("【用户问题】\n").append(abbreviate(userQuestion, 2_000)).append("\n\n");
        if (StringUtils.hasText(expectedBaseline)) {
            builder.append("【参考基线 / 期望输出】\n").append(abbreviate(expectedBaseline, 2_000)).append("\n\n");
        }
        builder.append("【Agent 完整执行轨迹】\n")
                .append(StringUtils.hasText(trajectoryText) ? trajectoryText : "（无轨迹数据）");
        return builder.toString();
    }

    /**
     * 简版共识合并：findings 按维度+标题聚合，双模型共识问题优先，分数取平均
     */
    private JudgeOutcome merge(List<ModelVerdict> verdicts) {
        Map<String, AggregatedFinding> aggregated = new LinkedHashMap<>();
        for (ModelVerdict verdict : verdicts) {
            for (QualityEvaluationStrategy.Finding finding : verdict.getFindings()) {
                String key = normalizeKey(finding.type() + "::" + finding.title());
                aggregated.computeIfAbsent(key, k -> new AggregatedFinding(finding)).merge(verdict.getModelId(), finding);
            }
        }
        List<Map<String, Object>> mergedFindings = aggregated.values().stream()
                .sorted(Comparator
                        // 注意：reversed() 作用于整个复合比较器，必须放在链尾整体取反，
                        // 链式 .comparingInt(x).reversed().thenComparingInt(y).reversed() 会
                        // 把第一段又反转回升序（经典 Comparator 陷阱）
                        .comparingInt(AggregatedFinding::consensusCount)
                        .thenComparingInt(AggregatedFinding::severityRank)
                        .reversed())
                .map(this::toFindingMap)
                .toList();

        int averageScore = (int) Math.round(verdicts.stream()
                .mapToInt(ModelVerdict::getScore)
                .average()
                .orElse(0));
        long consensusCount = aggregated.values().stream().filter(item -> item.consensusCount() > 1).count();
        boolean hasIssue = !mergedFindings.isEmpty() || verdicts.stream().anyMatch(ModelVerdict::isHasIssue);
        String summary = verdicts.size() + " 个 Judge 模型完成轨迹评审，共识问题 " + consensusCount
                + " 项，独立问题 " + Math.max(0, mergedFindings.size() - consensusCount)
                + " 项，综合评分 " + averageScore;
        return JudgeOutcome.builder()
                .score(averageScore)
                .hasIssue(hasIssue)
                .summary(summary)
                .consensusFindingCount((int) consensusCount)
                .uniqueFindingCount(mergedFindings.size())
                .findings(mergedFindings)
                .modelVerdicts(verdicts)
                .build();
    }

    private Map<String, Object> toFindingMap(AggregatedFinding finding) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", finding.type);
        map.put("severity", finding.severity);
        map.put("confidence", finding.averageConfidence());
        map.put("title", finding.title);
        map.put("description", finding.description);
        map.put("location", finding.location);
        map.put("suggestion", finding.suggestion);
        map.put("consensusCount", finding.consensusCount());
        map.put("modelIds", List.copyOf(finding.modelIds));
        return map;
    }

    private List<String> normalizeModelIds(List<String> modelIds) {
        if (modelIds == null || modelIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> dedup = new LinkedHashSet<>();
        for (String modelId : modelIds) {
            if (StringUtils.hasText(modelId)) {
                dedup.add(modelId.trim());
            }
            if (dedup.size() >= 2) {
                break;
            }
        }
        return new ArrayList<>(dedup);
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private String abbreviate(String text, int maxChars) {
        if (!StringUtils.hasText(text)) {
            return "-";
        }
        String cleaned = text.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= maxChars ? cleaned : cleaned.substring(0, maxChars) + "…(截断)";
    }

    // ------------------------------------------------------------------
    //  内部类型
    // ------------------------------------------------------------------

    /**
     * 单模型评审结论
     */
    @Data
    @Builder
    public static class ModelVerdict {
        private String modelId;
        private boolean hasIssue;
        private int score;
        private String summary;
        private List<QualityEvaluationStrategy.Finding> findings;
        private boolean timedOut;
        private boolean failed;

        static ModelVerdict timedOut(String modelId) {
            return ModelVerdict.builder()
                    .modelId(modelId)
                    .hasIssue(true)
                    .score(20)
                    .summary("模型 " + modelId + " 轨迹评审超时")
                    .findings(List.of(new QualityEvaluationStrategy.Finding(
                            "boundary_handling", "warning", 100,
                            "qa.infrastructure.judge_timeout",
                            "Judge 模型评审超时",
                            "模型 " + modelId + " 评审超过 " + JUDGE_TIMEOUT_SECONDS + " 秒",
                            "", "检查模型配置与网络连通性后重试")))
                    .timedOut(true)
                    .failed(true)
                    .build();
        }

        static ModelVerdict failed(String modelId, Throwable throwable) {
            String message = throwable != null && StringUtils.hasText(throwable.getMessage())
                    ? throwable.getMessage() : throwable == null ? "未知异常" : throwable.getClass().getSimpleName();
            return ModelVerdict.builder()
                    .modelId(modelId)
                    .hasIssue(true)
                    .score(20)
                    .summary("模型 " + modelId + " 轨迹评审失败")
                    .findings(List.of(new QualityEvaluationStrategy.Finding(
                            "boundary_handling", "warning", 100,
                            "qa.infrastructure.judge_failed",
                            "Judge 模型评审失败",
                            "模型 " + modelId + " 调用异常: " + message,
                            "", "检查模型配置、网络连通性后重试")))
                    .timedOut(false)
                    .failed(true)
                    .build();
        }
    }

    private static final class AggregatedFinding {
        private final String type;
        private String severity;
        private String title;
        private String description;
        private String location;
        private String suggestion;
        private int confidenceSum;
        private int confidenceCount;
        private final LinkedHashSet<String> modelIds = new LinkedHashSet<>();

        AggregatedFinding(QualityEvaluationStrategy.Finding finding) {
            this.type = finding.type();
            this.severity = finding.severity();
            this.title = finding.title();
            this.description = finding.description();
            this.location = finding.location();
            this.suggestion = finding.suggestion();
        }

        void merge(String modelId, QualityEvaluationStrategy.Finding finding) {
            this.modelIds.add(modelId);
            this.confidenceSum += finding.confidence();
            this.confidenceCount++;
            if (severityRank(finding.severity()) > severityRank(this.severity)) {
                this.severity = finding.severity();
            }
            if (finding.description() != null && this.description != null
                    && finding.description().length() > this.description.length()) {
                this.description = finding.description();
            }
            if (!StringUtils.hasText(this.location) && StringUtils.hasText(finding.location())) {
                this.location = finding.location();
            }
            if (!StringUtils.hasText(this.suggestion) && StringUtils.hasText(finding.suggestion())) {
                this.suggestion = finding.suggestion();
            }
            if (!StringUtils.hasText(this.title) && StringUtils.hasText(finding.title())) {
                this.title = finding.title();
            }
        }

        int averageConfidence() {
            return confidenceCount == 0 ? 0 : confidenceSum / confidenceCount;
        }

        int consensusCount() {
            return modelIds.size();
        }

        int severityRank() {
            return severityRank(severity);
        }

        static int severityRank(String severity) {
            return switch (severity == null ? "" : severity.toLowerCase(Locale.ROOT)) {
                case "critical" -> 3;
                case "warning" -> 2;
                case "info" -> 1;
                default -> 0;
            };
        }
    }

    /**
     * 轨迹评审合并结论
     */
    @Data
    @Builder
    public static class JudgeOutcome {
        /** 双模型平均分（0-100） */
        private int score;
        /** 是否发现问题 */
        private boolean hasIssue;
        /** 一句话总结 */
        private String summary;
        /** 双模型共识问题数 */
        private int consensusFindingCount;
        /** 合并去重后问题总数 */
        private int uniqueFindingCount;
        /** 合并后 findings（Map 列表，可直接落 JSONB） */
        private List<Map<String, Object>> findings;
        /** 各模型原始结论 */
        private List<ModelVerdict> modelVerdicts;
    }
}
