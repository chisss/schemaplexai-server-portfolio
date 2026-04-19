package com.schemaplexai.service.quality.pipeline;

import com.schemaplexai.common.enums.DeviationSeverityEnum;
import com.schemaplexai.common.enums.ExecutionStrategyEnum;
import com.schemaplexai.common.enums.GateDecisionEnum;
import com.schemaplexai.common.enums.QualityIssueTypeEnum;
import com.schemaplexai.common.enums.TaskStatusEnum;
import com.schemaplexai.common.enums.TriggerModeEnum;
import com.schemaplexai.model.entity.CrossReview;
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
        long startTime = System.currentTimeMillis();
        String strategy = request.executionStrategy();

        // 创建质量任务
        String taskId = createQualityTask(request);

        try {
            qualityTaskManager.markRunning(taskId);

            // Layer 1: 同步规则检查（始终执行）
            List<QualityEvaluationStrategy.Finding> allFindings = new ArrayList<>();
            List<QualityDetector.DetectionResult> ruleResults = executeRuleChecks(request);
            allFindings.addAll(convertDetectionFindings(ruleResults));

            int score = 100;
            String summary = "未发现问题";

            // Layer 2: 模型评估（SHORT_WAIT 和 ASYNC 策略执行）
            if (!ExecutionStrategyEnum.SYNC.getCode().equalsIgnoreCase(strategy)) {
                ModelEvalResult modelResult = executeModelEvaluation(request, taskId, strategy);
                if (modelResult != null) {
                    allFindings.addAll(modelResult.findings());
                    score = modelResult.score();
                    summary = modelResult.summary();
                }
            }

            // 去重合并 findings
            allFindings = deduplicateFindings(allFindings);

            long warningCount = allFindings.stream()
                    .filter(f -> !DeviationSeverityEnum.INFO.getCode().equals(f.severity()))
                    .count();

            // 标记成功
            Map<String, Object> resultSummary = new LinkedHashMap<>();
            resultSummary.put("executionStrategy", strategy);
            resultSummary.put("deviationCount", allFindings.size());
            resultSummary.put("warningCount", warningCount);
            resultSummary.put("score", score);
            qualityTaskManager.markSucceeded(taskId, allFindings.size(), allFindings.size(), 0, resultSummary);

            long durationMs = System.currentTimeMillis() - startTime;
            return new QualityCheckResult(
                    taskId, "pending_gate", allFindings, score, summary,
                    null, durationMs, strategy, TaskStatusEnum.SUCCEEDED.getCode(),
                    allFindings.size(), Math.toIntExact(warningCount)
            );

        } catch (Exception e) {
            String failureMessage = resolveFailureMessage(e);
            qualityTaskManager.markFailed(taskId, failureMessage);
            log.error("质量检查管线执行失败: specId={}, strategy={}", request.specId(), strategy, e);
            long durationMs = System.currentTimeMillis() - startTime;
            return new QualityCheckResult(
                    taskId, GateDecisionEnum.PASS.getCode(), List.of(), 100, "质量检查执行失败: " + failureMessage,
                    null, durationMs, strategy, TaskStatusEnum.FAILED.getCode(), 0, 0
            );
        }
    }

    /**
     * 异步触发质量检查（发送 MQ 消息，立即返回 taskId）
     */
    public String triggerAsync(QualityCheckRequest request) {
        String taskId = createQualityTask(request);
        log.info("异步质量检查已提交: taskId={}, specId={}", taskId, request.specId());
        return taskId;
    }

    // ==================== Layer 1: 同步规则检查 ====================

    private List<QualityDetector.DetectionResult> executeRuleChecks(QualityCheckRequest request) {
        List<QualityDetector.DetectionResult> results = new ArrayList<>();
        if (!StringUtils.hasText(request.targetContent())) {
            return results;
        }

        // 加载 Profile 关联的启用规则（按触发模式过滤）
        String triggerMode = resolveTriggerMode(request);
        List<QualityRule> activeRules = ruleConfigService.list(null, triggerMode);

        // 若无规则配置，降级为执行所有检测器
        if (activeRules.isEmpty()) {
            return executeAllDetectors(request);
        }

        // 按 dimensionCode 分组，每个维度取第一条匹配规则的 ruleConfig
        Map<String, QualityRule> dimensionRuleMap = activeRules.stream()
                .collect(Collectors.toMap(
                        QualityRule::getDimensionCode,
                        r -> r,
                        (existing, replacement) -> existing
                ));

        Set<String> enabledDimensions = dimensionRuleMap.keySet();

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
            try {
                QualityDetector.DetectionContext ctx = new QualityDetector.DetectionContext(
                        request.specId(), null, dimensionCode, null,
                        ruleConfig, request.targetContent()
                );
                QualityDetector.DetectionResult result = detector.detect(ctx);
                if (result != null && result.hasIssue()) {
                    results.add(result);
                }
            } catch (Exception e) {
                log.warn("规则检测器执行失败: type={}, dimensionCode={}, error={}", detector.getType(), dimensionCode, e.getMessage());
            }
        }
        return results;
    }

    /** 降级：执行所有注册检测器（无 Profile 规则时使用） */
    private List<QualityDetector.DetectionResult> executeAllDetectors(QualityCheckRequest request) {
        List<QualityDetector.DetectionResult> results = new ArrayList<>();
        for (QualityDetector detector : detectorRegistry.allDetectors().values()) {
            try {
                QualityDetector.DetectionContext ctx = new QualityDetector.DetectionContext(
                        request.specId(), null, detector.getType(), null,
                        Map.of(), request.targetContent()
                );
                QualityDetector.DetectionResult result = detector.detect(ctx);
                if (result != null && result.hasIssue()) {
                    results.add(result);
                }
            } catch (Exception e) {
                log.warn("规则检测器执行失败: type={}, error={}", detector.getType(), e.getMessage());
            }
        }
        return results;
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
        QualityProfile profile = resolveProfile(request);
        List<String> modelIds = profile == null ? List.of() : profileResolverService.listModelIds(profile.getId());
        String primaryModelId = modelIds.isEmpty() ? null : modelIds.getFirst();

        QualityEvaluationStrategy evalStrategy = strategyRegistry.getStrategy(request.issueType());
        if (evalStrategy == null || !StringUtils.hasText(primaryModelId)) {
            log.info("未配置可用质量评估模型，跳过模型评估: specId={}", request.specId());
            return null;
        }

        QualityReviewPolicy reviewPolicy = reviewPolicyService.resolvePolicy(
                profile, request.issueType(), resolveTriggerMode(request)
        );

        // 构建评估上下文
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
                Map.of("sourceType", request.sourceType()),
                reviewPolicy
        );

        QualityEvaluationStrategy.EvaluationResult evalResult;

        if (ExecutionStrategyEnum.SHORT_WAIT.getCode().equalsIgnoreCase(strategy)) {
            // 带超时的同步等待
            evalResult = executeWithTimeout(evalStrategy, evalContext);
        } else {
            // 直接同步执行（ASYNC 模式由 MQ Consumer 调用，此处不做异步）
            evalResult = evalStrategy.evaluate(evalContext);
        }

        if (evalResult == null) {
            return null;
        }

        // Layer 3: 交叉审查（多模型时异步触发）
        String crossReviewId = null;
        List<QualityEvaluationStrategy.Finding> crossFindings = List.of();
        if (profile != null && modelIds.size() > 1) {
            try {
                CrossReview crossReview = crossReviewExecutionService.createAndExecute(
                        request.specId(), taskId, profile.getId(), request.issueType(),
                        modelIds, request.sourceType(), null, request.targetContent(),
                        request.referenceContent(), request.tenantId()
                );
                if (crossReview != null) {
                    crossReviewId = crossReview.getId();
                }
            } catch (Exception e) {
                log.warn("交叉审查触发失败: specId={}, error={}", request.specId(), e.getMessage());
            }
        }

        // 合并结果
        List<QualityEvaluationStrategy.Finding> mergedFindings = new ArrayList<>(evalResult.findings());
        mergedFindings.addAll(crossFindings);

        return new ModelEvalResult(mergedFindings, evalResult.score(), evalResult.summary(), crossReviewId);
    }

    private QualityEvaluationStrategy.EvaluationResult executeWithTimeout(
            QualityEvaluationStrategy strategy,
            QualityEvaluationStrategy.EvaluationContext context) {
        try {
            CompletableFuture<QualityEvaluationStrategy.EvaluationResult> future =
                    CompletableFuture.supplyAsync(() -> strategy.evaluate(context));
            return future.get(SHORT_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("模型评估超时({}s)，降级为 pass: specId={}", SHORT_WAIT_TIMEOUT_SECONDS, context.specId());
            return QualityEvaluationStrategy.EvaluationResult.empty();
        } catch (Exception e) {
            log.error("模型评估异常: specId={}", context.specId(), e);
            return QualityEvaluationStrategy.EvaluationResult.empty();
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

    private String createQualityTask(QualityCheckRequest request) {
        String triggerMode = resolveTriggerMode(request);
        return qualityTaskManager.createTask(
                null, request.issueType(), triggerMode, request.sourceType(),
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

    /** 模型评估内部结果 */
    private record ModelEvalResult(
            List<QualityEvaluationStrategy.Finding> findings,
            int score,
            String summary,
            String crossReviewId
    ) {}
}
