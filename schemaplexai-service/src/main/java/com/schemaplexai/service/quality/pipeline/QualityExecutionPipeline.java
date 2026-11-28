package com.schemaplexai.service.quality.pipeline;

import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.enums.DeviationSeverityEnum;
import com.schemaplexai.common.enums.ExecutionStrategyEnum;
import com.schemaplexai.common.enums.GateDecisionEnum;
import com.schemaplexai.common.enums.QualityIssueTypeEnum;
import com.schemaplexai.common.enums.TaskStatusEnum;
import com.schemaplexai.common.enums.TriggerModeEnum;
import com.schemaplexai.model.entity.QualityProfile;
import com.schemaplexai.model.entity.QualityRule;
import com.schemaplexai.service.quality.QualityCrossReviewExecutionService;
import com.schemaplexai.service.quality.QualityProfileResolverService;
import com.schemaplexai.service.quality.RuleConfigService;
import com.schemaplexai.service.quality.detector.DetectorRegistry;
import com.schemaplexai.service.quality.detector.QualityDetector;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategyRegistry;
import com.schemaplexai.service.quality.strategy.QualityReviewPolicy;
import com.schemaplexai.service.quality.strategy.QualityReviewPolicyService;
import com.schemaplexai.service.quality.task.QualityTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 质量执行管线
 * 编排三层执行策略: SYNC(规则) -> SHORT_WAIT(模型评估) -> ASYNC(交叉审查)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityExecutionPipeline {

    /** SHORT_WAIT 模式下模型评估的最大等待时间（秒） */
    private static final int SHORT_WAIT_TIMEOUT_SECONDS = 15;
    private static final int INCOMPLETE_COVERAGE_SCORE_CAP = 59;
    private static final String MODEL_INFRASTRUCTURE_PREFIX = "qa.infrastructure.";
    private static final Set<String> MODEL_INFRASTRUCTURE_REASONS = Set.of(
            "model_not_configured", "model_not_found", "model_evaluation_failed",
            "model_empty_response", "model_response_invalid"
    );

    private final DetectorRegistry detectorRegistry;
    private final QualityEvaluationStrategyRegistry strategyRegistry;
    private final QualityProfileResolverService profileResolverService;
    private final QualityReviewPolicyService reviewPolicyService;
    private final QualityCrossReviewExecutionService crossReviewExecutionService;
    private final QualityTaskManager qualityTaskManager;
    private final RuleConfigService ruleConfigService;

    /**
     * 执行质量检查管线
     */
    public QualityCheckResult execute(QualityCheckRequest request) {
        return execute(request, null);
    }

    public QualityCheckResult execute(QualityCheckRequest request, String requestedTaskId) {
        long startTime = System.currentTimeMillis();
        String strategy = request.executionStrategy();

        // 创建质量任务
        String taskId = createQualityTask(request, requestedTaskId);

        try {
            qualityTaskManager.markRunning(taskId);

            // Layer 1: 同步规则检查（始终执行）
            List<QualityEvaluationStrategy.Finding> allFindings = new ArrayList<>();
            RuleChecks ruleChecks = executeRuleChecks(request);
            allFindings.addAll(convertDetectionFindings(ruleChecks.findings));

            int score = 100;
            String summary = "未发现问题";

            // Layer 2: 模型评估（SHORT_WAIT 和 ASYNC 策略执行）
            ModelEvalResult model = executeModelEvaluation(request, taskId, strategy);
            if (model.result() != null) {
                allFindings.addAll(model.result().findings());
                score = model.result().score();
                summary = model.result().summary();
            }

            // 去重合并 findings
            allFindings = deduplicateFindings(allFindings);

            long warningCount = allFindings.stream()
                    .filter(f -> !DeviationSeverityEnum.INFO.getCode().equals(f.severity()))
                    .count();
            Map<String, Object> coverage = buildCoverage(ruleChecks, model);
            boolean coverageIncomplete = Boolean.TRUE.equals(coverage.get("requiredIncomplete"));
            if (coverageIncomplete) {
                score = Math.min(score, INCOMPLETE_COVERAGE_SCORE_CAP);
                summary = mergeSummary(summary, buildCoverageSummary(coverage));
            }
            String taskStatus = coverageIncomplete
                    ? TaskStatusEnum.FAILED.getCode() : TaskStatusEnum.SUCCEEDED.getCode();
            String gateDecision = coverageIncomplete ? GateDecisionEnum.ERROR.getCode() : "pending_gate";

            // 标记成功
            Map<String, Object> resultSummary = new LinkedHashMap<>();
            resultSummary.put("executionStrategy", strategy);
            resultSummary.put("deviationCount", allFindings.size());
            resultSummary.put("warningCount", warningCount);
            resultSummary.put("score", score);
            resultSummary.put("coverage", coverage);
            if (QualityIssueTypeEnum.DEVIATION.getCode().equals(request.issueType())
                    && TriggerModeEnum.MANUAL.getCode().equals(resolveTriggerMode(request))) {
                qualityTaskManager.completeManualDeviationTask(
                        taskId, request, allFindings, resultSummary, coverageIncomplete);
            } else if (coverageIncomplete) {
                qualityTaskManager.markFailed(taskId, summary, resultSummary);
            } else {
                qualityTaskManager.markSucceeded(taskId, allFindings.size(), allFindings.size(), 0, resultSummary);
            }

            long durationMs = System.currentTimeMillis() - startTime;
            return new QualityCheckResult(
                    taskId, gateDecision, allFindings, score, summary,
                    null, durationMs, strategy, taskStatus,
                    allFindings.size(), Math.toIntExact(warningCount), coverage
            );

        } catch (Exception e) {
            String failureMessage = resolveFailureMessage(e);
            qualityTaskManager.markFailed(taskId, failureMessage);
            log.error("质量检查管线执行失败: specId={}, strategy={}", request.specId(), strategy, e);
            long durationMs = System.currentTimeMillis() - startTime;
            return new QualityCheckResult(
                    taskId, GateDecisionEnum.ERROR.getCode(), List.of(), 0, "质量检查执行失败: " + failureMessage,
                    null, durationMs, strategy, TaskStatusEnum.FAILED.getCode(), 0, 0,
                    Map.of("status", "error", "requiredIncomplete", true,
                            "dimensions", Map.of(), "rules", Map.of(),
                            "model", Map.of("status", "unknown", "required", false, "reason", "pipeline_failed"))
            );
        }
    }

    /**
     * 异步触发质量检查（发送 MQ 消息，立即返回 taskId）
     */
    public String triggerAsync(QualityCheckRequest request) {
        String taskId = createQualityTask(request, null);
        log.info("异步质量检查已提交: taskId={}, specId={}", taskId, request.specId());
        return taskId;
    }

    // ==================== Layer 1: 同步规则检查 ====================

    private RuleChecks executeRuleChecks(QualityCheckRequest request) {
        if (!StringUtils.hasText(request.targetContent())) {
            RuleChecks checks = new RuleChecks("no_content", List.of());
            checks.markDimension("content", "unknown", true, "no_content");
            return checks;
        }

        // 沿用按触发模式加载配置的方式，启用数量另按规则状态统计。
        String triggerMode = resolveTriggerMode(request);
        List<QualityRule> configuredRules = ruleConfigService.list(null, triggerMode);

        // 若无规则配置，降级为执行所有检测器
        if (configuredRules.isEmpty()) {
            return executeAllDetectors(request);
        }

        RuleChecks checks = new RuleChecks("configured", configuredRules);
        // 按 dimensionCode 分组，每个维度取第一条匹配规则的 ruleConfig
        Map<String, QualityRule> dimensionRuleMap = configuredRules.stream()
                .filter(rule -> CommonConstant.STATUS_ACTIVE.equalsIgnoreCase(rule.getStatus()))
                .collect(Collectors.toMap(
                        QualityRule::getDimensionCode,
                        r -> r,
                        (existing, replacement) -> existing
                ));

        Set<String> enabledDimensions = configuredRules.stream()
                .filter(rule -> CommonConstant.STATUS_ACTIVE.equalsIgnoreCase(rule.getStatus()))
                .map(QualityRule::getDimensionCode)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (QualityDetector detector : detectorRegistry.allDetectors().values()) {
            // 跳过不支持任何已启用维度的检测器
            boolean matched = enabledDimensions.stream().anyMatch(detector::supports);
            if (!matched) {
                continue;
            }
            // 找到该检测器对应的规则，传入规则级 ruleConfig
            Map<String, Object> ruleConfig = enabledDimensions.stream()
                    .filter(detector::supports)
                    .map(dimensionRuleMap::get)
                    .filter(r -> r != null && r.getRuleConfig() != null)
                    .findFirst()
                    .map(QualityRule::getRuleConfig)
                    .orElse(Map.of());
            String dimensionCode = enabledDimensions.stream()
                    .filter(detector::supports)
                    .findFirst()
                    .orElse(detector.getType());
            checks.run(detector, new QualityDetector.DetectionContext(
                    request.specId(), null, dimensionCode, null, ruleConfig, request.targetContent()),
                    dimensionCode, true);
        }
        enabledDimensions.stream()
                .filter(dimension -> !checks.hasDimension(dimension))
                .forEach(dimension -> checks.markDimension(dimension, "skipped", true, "no_detector"));
        return checks;
    }

    /** 降级：执行所有注册检测器（无 Profile 规则时使用） */
    private RuleChecks executeAllDetectors(QualityCheckRequest request) {
        RuleChecks checks = new RuleChecks("all_detectors", List.of());
        for (QualityDetector detector : detectorRegistry.allDetectors().values()) {
            checks.run(detector, new QualityDetector.DetectionContext(
                    request.specId(), null, detector.getType(), null, Map.of(), request.targetContent()),
                    detector.getType(), true);
        }
        return checks;
    }

    private List<QualityEvaluationStrategy.Finding> convertDetectionFindings(List<QualityDetector.DetectionResult> results) {
        List<QualityEvaluationStrategy.Finding> findings = new ArrayList<>();
        for (QualityDetector.DetectionResult result : results) {
            if (result.hasIssue()) {
                findings.add(new QualityEvaluationStrategy.Finding(
                        "structural", result.severity(), 80, null,
                        result.message(), result.message(), null, null
                ));
            }
        }
        return findings;
    }

    // ==================== Layer 2: 模型评估 ====================

    private ModelEvalResult executeModelEvaluation(QualityCheckRequest request, String taskId, String strategy) {
        if (ExecutionStrategyEnum.SYNC.getCode().equalsIgnoreCase(strategy)) {
            return ModelEvalResult.notApplicable("strategy_disabled", null, null);
        }
        QualityProfile profile = resolveProfile(request);
        if (profile == null) {
            return ModelEvalResult.notApplicable("no_profile", null, null);
        }
        String profileId = profile.getId();
        List<String> modelIds = profileResolverService.listModelIds(profileId);
        String primaryModelId = modelIds.isEmpty() ? null : modelIds.getFirst();
        if (!StringUtils.hasText(primaryModelId)) {
            return ModelEvalResult.failed("no_model", profileId, null, true);
        }

        QualityEvaluationStrategy evalStrategy = strategyRegistry.getStrategy(request.issueType());
        if (evalStrategy == null) {
            return ModelEvalResult.failed("unsupported_issue_type", profileId, primaryModelId, true);
        }

        QualityReviewPolicy reviewPolicy = reviewPolicyService.resolvePolicy(
                profile, request.issueType(), resolveTriggerMode(request)
        );

        // 构建评估上下文
        boolean hasOriginalTask = StringUtils.hasText(request.originalTask());
        Map<String, Object> extraParams = hasOriginalTask
                ? Map.of("sourceType", request.sourceType(), "originalTask", request.originalTask())
                : Map.of("sourceType", request.sourceType());
        QualityEvaluationStrategy.EvaluationContext evalContext = new QualityEvaluationStrategy.EvaluationContext(
                request.specId(),
                request.issueType(),
                request.docType(),
                request.nodeId(),
                request.nodeLabel(),
                request.targetContent(),
                request.referenceContent(),
                primaryModelId,
                null,
                extraParams,
                reviewPolicy
        );

        ModelEvalResult assessment = ExecutionStrategyEnum.SHORT_WAIT.getCode().equalsIgnoreCase(strategy)
                ? executeWithTimeout(evalStrategy, evalContext, profileId)
                : ModelEvalResult.fromStrategy(evalStrategy.evaluate(evalContext), profileId, primaryModelId);

        // 模型有结果时，按原配置触发交叉审查。
        if (assessment.result() != null && modelIds.size() > 1) {
            try {
                crossReviewExecutionService.createAndExecute(
                        request.specId(), taskId, profileId, request.issueType(),
                        modelIds, request.sourceType(), null, request.targetContent(),
                        request.referenceContent(), request.tenantId(), request.originalTask()
                );
            } catch (Exception e) {
                log.warn("交叉审查触发失败: specId={}, error={}", request.specId(), e.getMessage());
            }
        }

        return assessment;
    }

    private ModelEvalResult executeWithTimeout(
            QualityEvaluationStrategy strategy,
            QualityEvaluationStrategy.EvaluationContext context,
            String profileId) {
        try {
            CompletableFuture<QualityEvaluationStrategy.EvaluationResult> future =
                    CompletableFuture.supplyAsync(() -> strategy.evaluate(context));
            return ModelEvalResult.fromStrategy(
                    future.get(SHORT_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS), profileId, context.modelId());
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("模型评估超时({}s)，按覆盖不完整处理: specId={}", SHORT_WAIT_TIMEOUT_SECONDS, context.specId());
            return ModelEvalResult.failed("timeout", profileId, context.modelId(), true);
        } catch (Exception e) {
            log.error("模型评估异常: specId={}", context.specId(), e);
            return ModelEvalResult.failed("evaluation_error", profileId, context.modelId(), true);
        }
    }

    // ==================== 辅助方法 ====================

    private QualityProfile resolveProfile(QualityCheckRequest request) {
        if (StringUtils.hasText(request.profileId())) {
            return profileResolverService.resolveProfileById(request.profileId());
        }
        return profileResolverService.resolveProfile(
                request.specId(), request.workflowTemplateId(), null,
                request.issueType(), request.sourceType()
        );
    }

    private String createQualityTask(QualityCheckRequest request, String taskId) {
        String triggerMode = resolveTriggerMode(request);
        return qualityTaskManager.createTask(
                taskId, request.issueType(), triggerMode, request.sourceType(),
                null, request.tenantId(), request.specId(),
                request.workflowTemplateId(), request.agentExecutionId(),
                request.profileId(), null,
                Map.of("executionStrategy", request.executionStrategy())
        );
    }

    private String resolveTriggerMode(QualityCheckRequest request) {
        if (TriggerModeEnum.MANUAL.getCode().equalsIgnoreCase(request.sourceType())) {
            return TriggerModeEnum.MANUAL.getCode();
        }
        if (QualityIssueTypeEnum.INTENT_DEFECT.getCode().equalsIgnoreCase(request.issueType())) {
            return TriggerModeEnum.EVENT.getCode();
        }
        return TriggerModeEnum.AGENT_EXECUTION.getCode();
    }

    private List<QualityEvaluationStrategy.Finding> deduplicateFindings(List<QualityEvaluationStrategy.Finding> findings) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<QualityEvaluationStrategy.Finding> result = new ArrayList<>();
        for (QualityEvaluationStrategy.Finding f : findings) {
            String key = buildFindingKey(f);
            if (seen.add(key)) {
                result.add(f);
            }
        }
        return result;
    }

    private String buildFindingKey(QualityEvaluationStrategy.Finding finding) {
        String type = normalize(finding.type());
        String title = normalize(finding.title());
        String location = normalize(finding.location());
        if (title.isEmpty() && location.isEmpty()) {
            return type + "::" + normalize(finding.description());
        }
        return type + "::" + title + "::" + location;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private String resolveFailureMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return StringUtils.hasText(current.getMessage()) ? current.getMessage() : current.getClass().getSimpleName();
    }

    private Map<String, Object> buildCoverage(RuleChecks ruleChecks, ModelEvalResult model) {
        Map<String, Object> ruleCoverage = ruleChecks.coverage();
        Map<String, Object> modelCoverage = model.coverage();
        boolean requiredIncomplete = Boolean.TRUE.equals(ruleCoverage.get("requiredIncomplete"))
                || Boolean.TRUE.equals(modelCoverage.get("requiredIncomplete"));
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("status", requiredIncomplete ? "incomplete" : "complete");
        coverage.put("requiredIncomplete", requiredIncomplete);
        coverage.put("dimensions", ruleCoverage.get("dimensions"));
        coverage.put("rules", ruleCoverage);
        coverage.put("model", modelCoverage);
        coverage.put("crossReview", Map.of(
                "status", "unknown",
                "required", false,
                "reason", "async_not_awaited"
        ));
        return coverage;
    }

    private String buildCoverageSummary(Map<String, Object> coverage) {
        List<String> incomplete = new ArrayList<>();
        Map<?, ?> dimensions = coverage.get("dimensions") instanceof Map<?, ?> map ? map : Map.of();
        dimensions.forEach((key, value) -> {
            if (value instanceof Map<?, ?> dimension
                    && Boolean.TRUE.equals(dimension.get("required"))
                    && !"passed".equalsIgnoreCase(String.valueOf(dimension.get("status")))) {
                incomplete.add(key + "=" + dimension.get("status"));
            }
        });
        if (coverage.get("model") instanceof Map<?, ?> model
                && Boolean.TRUE.equals(model.get("required"))
                && !"passed".equalsIgnoreCase(String.valueOf(model.get("status")))) {
            incomplete.add("model=" + model.get("status"));
        }
        return incomplete.isEmpty()
                ? "质量检查覆盖不完整"
                : "质量检查覆盖不完整: " + String.join(", ", incomplete);
    }

    private String mergeSummary(String summary, String addition) {
        if (!StringUtils.hasText(summary)) {
            return addition;
        }
        if (!StringUtils.hasText(addition) || summary.contains(addition)) {
            return summary;
        }
        return summary + "；" + addition;
    }

    private static class RuleChecks {
        private final String mode;
        private final int configured;
        private final long enabled;
        private final List<QualityDetector.DetectionResult> findings = new ArrayList<>();
        private final Map<String, Map<String, Object>> dimensions = new LinkedHashMap<>();
        private int attempted;
        private int completed;

        private RuleChecks(String mode, List<QualityRule> rules) {
            this.mode = mode;
            configured = rules.size();
            enabled = rules.stream()
                    .filter(rule -> CommonConstant.STATUS_ACTIVE.equalsIgnoreCase(rule.getStatus()))
                    .count();
        }

        private void run(QualityDetector detector, QualityDetector.DetectionContext context,
                         String dimensionCode, boolean required) {
            attempted++;
            try {
                QualityDetector.DetectionResult result = detector.detect(context);
                if (result == null) {
                    markDimension(dimensionCode, "unknown", required, "no_result");
                    return;
                }
                completed++;
                if (result.hasIssue()) {
                    findings.add(result);
                    markDimension(dimensionCode, "failed", required, "issues_found");
                } else {
                    markDimension(dimensionCode, "passed", required, null);
                }
            } catch (Exception e) {
                log.warn("规则检测器执行失败: dimensionCode={}, error={}", context.dimensionCode(), e.getMessage());
                markDimension(dimensionCode, "unknown", required, "detector_error");
            }
        }

        private Map<String, Object> coverage() {
            boolean requiredIncomplete = dimensions.values().stream()
                    .anyMatch(dimension -> Boolean.TRUE.equals(dimension.get("required"))
                            && !"passed".equalsIgnoreCase(String.valueOf(dimension.get("status"))));
            Map<String, Object> coverage = new LinkedHashMap<>();
            coverage.put("mode", mode);
            coverage.put("configuredRuleCount", configured);
            coverage.put("enabledRuleCount", enabled);
            coverage.put("detectorsAttempted", attempted);
            coverage.put("detectorsCompleted", completed);
            coverage.put("detectorsFailed", attempted - completed);
            coverage.put("requiredIncomplete", requiredIncomplete);
            coverage.put("dimensions", dimensions);
            return coverage;
        }

        private void markDimension(String dimensionCode, String status, boolean required, String reason) {
            String key = StringUtils.hasText(dimensionCode) ? dimensionCode : "unknown";
            Map<String, Object> current = dimensions.get(key);
            String currentStatus = current == null ? null : String.valueOf(current.get("status"));
            if (current != null && coverageRank(currentStatus) >= coverageRank(status)) {
                return;
            }
            Map<String, Object> dimension = new LinkedHashMap<>();
            dimension.put("status", status);
            dimension.put("required", required);
            if (reason != null) {
                dimension.put("reason", reason);
            }
            dimensions.put(key, dimension);
        }

        private boolean hasDimension(String dimensionCode) {
            return dimensions.containsKey(dimensionCode);
        }

        private int coverageRank(String status) {
            return switch (status == null ? "" : status.toLowerCase()) {
                case "passed" -> 0;
                case "not_applicable" -> 1;
                case "skipped" -> 2;
                case "unknown" -> 3;
                case "failed" -> 4;
                default -> 3;
            };
        }
    }

    /** 策略结果可能来自降级路径，模型调用的完成状态需要可验证的来源。 */
    private record ModelEvalResult(
            QualityEvaluationStrategy.EvaluationResult result,
            String status,
            String reason,
            String profileId,
            String modelId,
            boolean required
    ) {
        private static ModelEvalResult notApplicable(String reason, String profileId, String modelId) {
            return new ModelEvalResult(null, "not_applicable", reason, profileId, modelId, false);
        }

        private static ModelEvalResult failed(String reason, String profileId, String modelId, boolean required) {
            return new ModelEvalResult(null, "failed", reason, profileId, modelId, required);
        }

        private static ModelEvalResult fromStrategy(
                QualityEvaluationStrategy.EvaluationResult result, String profileId, String modelId) {
            if (result == null) {
                return new ModelEvalResult(null, "unknown", "no_result", profileId, modelId, true);
            }
            var assessment = result.modelAssessment();
            if (assessment != null) {
                return new ModelEvalResult(result, assessment.status(), assessment.reason(),
                        profileId, modelId, true);
            }
            String reason = result.findings().stream()
                    .map(QualityEvaluationStrategy.Finding::ruleCode)
                    .filter(code -> code != null && code.startsWith(MODEL_INFRASTRUCTURE_PREFIX))
                    .map(code -> code.substring(MODEL_INFRASTRUCTURE_PREFIX.length()))
                    .filter(MODEL_INFRASTRUCTURE_REASONS::contains)
                    .findFirst()
                    .orElse("unverified_result");
            return new ModelEvalResult(result, "unknown", reason, profileId, modelId, true);
        }

        private Map<String, Object> coverage() {
            String coverageStatus = resolveCoverageStatus();
            boolean requiredIncomplete = required && !"passed".equals(coverageStatus);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", coverageStatus);
            data.put("modelRunStatus", status);
            data.put("required", required);
            data.put("requiredIncomplete", requiredIncomplete);
            if (reason != null) {
                data.put("reason", reason);
            }
            if (profileId != null) {
                data.put("resolvedProfileId", profileId);
            }
            if (modelId != null) {
                data.put("resolvedModelId", modelId);
            }
            return data;
        }

        private String resolveCoverageStatus() {
            if ("not_applicable".equalsIgnoreCase(status)) {
                return "not_applicable";
            }
            if ("completed".equalsIgnoreCase(status)) {
                return result != null && !result.hasIssue() ? "passed" : "failed";
            }
            if ("skipped".equalsIgnoreCase(status)) {
                return required ? "failed" : "not_applicable";
            }
            if ("failed".equalsIgnoreCase(status)) {
                return "failed";
            }
            return "unknown";
        }
    }
}
