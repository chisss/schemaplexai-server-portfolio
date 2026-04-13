package com.schemaplexai.service.quality.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.TaskStatusEnum;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.CrossReviewMapper;
import com.schemaplexai.dao.mapper.QualityProfileMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.SpecDocumentMapper;
import com.schemaplexai.model.entity.CrossReview;
import com.schemaplexai.model.entity.QualityProfile;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.SpecDocument;
import com.schemaplexai.service.quality.QualityCrossReviewExecutionService;
import com.schemaplexai.service.quality.strategy.ModelBasedQualityEvaluator;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy;
import com.schemaplexai.service.quality.strategy.QualityReviewPolicy;
import com.schemaplexai.service.quality.strategy.QualityReviewPolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
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
 * 质量交叉审查执行服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityCrossReviewExecutionServiceImpl implements QualityCrossReviewExecutionService {

    private static final int MAX_REVIEWER_COUNT = 2;
    private static final int REVIEW_CONTENT_MAX_TOTAL_CHARS = 12_000;
    private static final int REVIEW_CONTENT_HEAD_CHARS = 2_400;
    private static final int REVIEW_CONTENT_TAIL_CHARS = 900;
    private static final int REVIEW_EXECUTION_TIMEOUT_SECONDS = 90;

    private static final String CROSS_REVIEW_SYSTEM_PROMPT = """
            你是 SchemaPlexAI 质量保障中心的独立交叉审查模型。
            你的职责是基于参考规范、质量维度和启用规则，对待审查内容给出独立、可复核、可执行的质量结论。

            审查要求：
            1. 只输出高置信度问题，不允许为了凑数输出含糊意见。
            2. 问题描述必须对应具体内容片段、章节、位置或上下文。
            3. 若命中具体规则，ruleCode 必须返回规则编码；若只命中维度要求，则返回 qa.dimension.<type>。
            4. 如果 hasIssue=true，则 findings 至少返回 1 条，且 summary 中提到的关键问题必须在 findings 中逐条体现。
            5. 只有在确认没有高置信度问题时，才允许 hasIssue=false、findings 返回空数组、score 返回 90-100。
            6. 禁止输出 hasIssue=true 但 findings=[] 的结果；若问题属于整体设计层面，location 至少填写“整体设计”或明确章节名。

            请严格按照以下 JSON 格式返回，不要添加任何额外说明：
            {
              "hasIssue": true/false,
              "score": 0-100,
              "summary": "一句话总结",
              "findings": [
                {
                  "ruleCode": "规则编码或 qa.dimension.<type>",
                  "type": "structural|semantic|omission|vagueness|ambiguity|contradiction|missing_acceptance",
                  "severity": "critical|warning|info",
                  "confidence": 0-100,
                  "title": "问题标题",
                  "description": "问题描述",
                  "location": "问题位置",
                  "suggestion": "改进建议"
                }
              ]
            }
            """;

    private final CrossReviewMapper crossReviewMapper;
    private final SpecDocumentMapper specDocumentMapper;
    private final QualityProfileMapper qualityProfileMapper;
    private final SpecMapper specMapper;
    private final ModelBasedQualityEvaluator modelEvaluator;
    private final QualityReviewPolicyService reviewPolicyService;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public CrossReview createAndExecute(String specId,
                                        String taskId,
                                        String profileId,
                                        String issueType,
                                        List<String> modelIds,
                                        String sourceType,
                                        String sourceAgentId,
                                        String targetContent,
                                        String tenantId) {
        List<String> normalizedModelIds = normalizeModelIds(modelIds);
        if (normalizedModelIds.isEmpty()) {
            return null;
        }
        QualityProfile profile = StringUtils.hasText(profileId) ? qualityProfileMapper.selectById(profileId) : null;
        String resolvedTenantId = resolveTenantId(tenantId, profile, specId);
        if (!StringUtils.hasText(resolvedTenantId)) {
            log.warn("交叉审查缺少租户信息，跳过执行: specId={}, taskId={}, profileId={}", specId, taskId, profileId);
            return null;
        }
        CrossReview entity = new CrossReview();
        entity.setTenantId(resolvedTenantId);
        entity.setSpecId(specId);
        entity.setTaskId(taskId);
        entity.setProfileId(profileId);
        entity.setIssueType(StringUtils.hasText(issueType) ? issueType : "both");
        entity.setModelAId(normalizedModelIds.getFirst());
        entity.setModelBId(normalizedModelIds.size() > 1 ? normalizedModelIds.get(1) : null);
        entity.setSourceType(sourceType);
        entity.setSourceAgentId(sourceAgentId);
        entity.setStatus(TaskStatusEnum.RUNNING.getCode());
        entity.setStartedAt(LocalDateTime.now());
        entity.setCreatedBy(StringUtils.hasText(sourceAgentId) ? sourceAgentId : SecurityUtil.getCurrentUserId());

        try {
            crossReviewMapper.insert(entity);
            Spec spec = StringUtils.hasText(specId) ? specMapper.selectById(specId) : null;
            String content = StringUtils.hasText(targetContent) ? targetContent : loadSpecContent(specId);
            String referenceContent = resolveReferenceContent(issueType, specId);
            QualityReviewPolicy reviewPolicy = reviewPolicyService.resolvePolicy(
                    profile, issueType, resolvePolicyTriggerMode(issueType, sourceType)
            );
            String systemPrompt = buildSystemPrompt(reviewPolicy);
            String userPrompt = buildUserPrompt(spec, issueType, content, referenceContent);
            List<ModelReview> reviews = executeReviews(normalizedModelIds, systemPrompt, userPrompt, reviewPolicy);
            Map<String, Object> mergedResult = buildMergedResult(reviews);

            CrossReview patch = new CrossReview();
            patch.setId(entity.getId());
            patch.setModelAResult(toResultMap(reviews.getFirst()));
            patch.setModelBResult(reviews.size() > 1 ? toResultMap(reviews.get(1)) : null);
            patch.setMergedResult(mergedResult);
            patch.setSummary(buildSummaryMap(reviews, mergedResult, reviewPolicy));
            patch.setStatus(TaskStatusEnum.SUCCEEDED.getCode());
            patch.setCompletedAt(LocalDateTime.now());
            crossReviewMapper.updateById(patch);
            return crossReviewMapper.selectById(entity.getId());
        } catch (Exception ex) {
            log.error("执行交叉审查失败: specId={}, profileId={}", specId, profileId, ex);
            if (StringUtils.hasText(entity.getId())) {
                try {
                    CrossReview patch = new CrossReview();
                    patch.setId(entity.getId());
                    patch.setStatus(TaskStatusEnum.FAILED.getCode());
                    patch.setErrorMessage(resolveFailureMessage(ex));
                    patch.setCompletedAt(LocalDateTime.now());
                    crossReviewMapper.updateById(patch);
                } catch (Exception patchException) {
                    log.warn("回写交叉审查失败状态失败: reviewId={}", entity.getId(), patchException);
                }
                return crossReviewMapper.selectById(entity.getId());
            }
            return null;
        }
    }

    private String buildSystemPrompt(QualityReviewPolicy reviewPolicy) {
        StringBuilder builder = new StringBuilder(CROSS_REVIEW_SYSTEM_PROMPT);
        if (reviewPolicy != null) {
            builder.append("\n\n").append(reviewPolicyService.renderPromptBlock(reviewPolicy));
        }
        return builder.toString();
    }

    private String buildUserPrompt(Spec spec, String issueType, String content, String referenceContent) {
        boolean marketingSpec = isMarketingSpec(spec);
        String typeDesc = resolveReviewTargetLabel(marketingSpec, issueType);
        String compactReferenceContent = compactReviewContent(referenceContent);
        String compactContent = compactReviewContent(content);
        StringBuilder builder = new StringBuilder("请对以下").append(typeDesc).append("进行独立交叉审查。\n\n");
        if (StringUtils.hasText(compactReferenceContent)) {
            builder.append(marketingSpec ? "【营销目标 / 审查基线】\n" : "【参考规范 / 上下文】\n")
                    .append(compactReferenceContent)
                    .append("\n\n");
        }
        builder.append("【待审查内容】\n")
                .append(StringUtils.hasText(compactContent) ? compactContent : "（内容为空）")
                .append("\n\n请优先聚焦")
                .append(resolveReviewFocus(marketingSpec, issueType))
                .append("。");
        return builder.toString();
    }

    private String resolveReviewTargetLabel(boolean marketingSpec, String issueType) {
        if (marketingSpec) {
            return switch (issueType == null ? "" : issueType.toLowerCase(Locale.ROOT)) {
                case "intent_defect" -> "营销 Spec / 文案需求";
                case "deviation" -> "营销交付物 / 最终文案";
                default -> "营销交付内容";
            };
        }
        return switch (issueType == null ? "" : issueType.toLowerCase(Locale.ROOT)) {
            case "intent_defect" -> "Spec / 文档内容";
            case "deviation" -> "Agent / Workflow 产出";
            default -> "AI 产出";
        };
    }

    private String resolveReviewFocus(boolean marketingSpec, String issueType) {
        if (marketingSpec) {
            return "交付内容是否完整覆盖目标渠道与必备章节、卖点与目标受众是否匹配、是否存在夸大承诺或不可验证表述、变量与数值是否前后一致、语言本地化是否自然、CTA 与合规风险提示是否清晰，以及是否混入过程稿或非最终交付内容";
        }
        return "intent_defect".equalsIgnoreCase(issueType)
                ? "结构矛盾、需求歧义、验收标准缺失、约束遗漏与语义冲突"
                : "结构矛盾、接口契约、验收标准、幂等性、数据一致性、异常处理与可观测性";
    }

    private boolean isMarketingSpec(Spec spec) {
        return spec != null && "marketing".equalsIgnoreCase(spec.getSpecType());
    }

    private List<ModelReview> executeReviews(List<String> modelIds,
                                             String systemPrompt,
                                             String userPrompt,
                                             QualityReviewPolicy reviewPolicy) {
        List<CompletableFuture<ModelReview>> futures = modelIds.stream()
                .map(modelId -> CompletableFuture
                        .supplyAsync(() -> executeSingleReview(modelId, systemPrompt, userPrompt, reviewPolicy))
                        .completeOnTimeout(
                                buildTimedOutModelReview(modelId),
                                REVIEW_EXECUTION_TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        )
                        .exceptionally(ex -> buildFailedModelReview(modelId, ex)))
                .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private ModelReview executeSingleReview(String modelId,
                                            String systemPrompt,
                                            String userPrompt,
                                            QualityReviewPolicy reviewPolicy) {
        QualityEvaluationStrategy.EvaluationResult rawResult =
                modelEvaluator.evaluate(modelId, systemPrompt, userPrompt);
        QualityEvaluationStrategy.EvaluationResult finalResult =
                reviewPolicyService.applyPolicy(rawResult, reviewPolicy);
        return new ModelReview(modelId, finalResult);
    }

    private ModelReview buildTimedOutModelReview(String modelId) {
        return buildFailedModelReview(
                modelId,
                new TimeoutException("交叉审查模型执行超时，超过 " + REVIEW_EXECUTION_TIMEOUT_SECONDS + " 秒"),
                false
        );
    }

    private ModelReview buildFailedModelReview(String modelId, Throwable throwable) {
        return buildFailedModelReview(modelId, throwable, true);
    }

    private ModelReview buildFailedModelReview(String modelId, Throwable throwable, boolean logFailure) {
        String failureMessage = resolveFailureMessage(throwable);
        if (logFailure) {
            log.warn("交叉审查单模型执行失败: modelId={}, error={}", modelId, failureMessage);
        }
        QualityEvaluationStrategy.EvaluationResult failedResult = new QualityEvaluationStrategy.EvaluationResult(
                true,
                20,
                "模型 " + modelId + " 交叉审查失败",
                List.of(new QualityEvaluationStrategy.Finding(
                        "omission",
                        "critical",
                        100,
                        "qa.infrastructure.model_evaluation_failed",
                        "质量评估模型调用失败",
                        "模型 " + modelId + " 调用异常: " + failureMessage,
                        "",
                        "检查模型配置、网络连通性和超时设置后重试"
                ))
        );
        return new ModelReview(modelId, failedResult);
    }

    private Map<String, Object> toResultMap(ModelReview review) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("modelId", review.modelId());
        map.put("hasIssue", review.result().hasIssue());
        map.put("score", review.result().score());
        map.put("summary", review.result().summary());
        map.put("findings", review.result().findings().stream().map(finding -> {
            Map<String, Object> findingMap = new LinkedHashMap<>();
            findingMap.put("ruleCode", finding.ruleCode());
            findingMap.put("type", finding.type());
            findingMap.put("severity", finding.severity());
            findingMap.put("confidence", finding.confidence());
            findingMap.put("title", finding.title());
            findingMap.put("description", finding.description());
            findingMap.put("location", finding.location());
            findingMap.put("suggestion", finding.suggestion());
            return findingMap;
        }).toList());
        return map;
    }

    private Map<String, Object> buildMergedResult(List<ModelReview> reviews) {
        Map<String, AggregatedFinding> aggregated = new LinkedHashMap<>();
        for (ModelReview review : reviews) {
            for (QualityEvaluationStrategy.Finding finding : review.result().findings()) {
                QualityEvaluationStrategy.Finding normalized = reviewPolicyService.normalizeFinding(finding);
                aggregated.computeIfAbsent(buildFindingKey(normalized), key -> new AggregatedFinding(normalized))
                        .merge(review.modelId(), normalized);
            }
        }
        List<Map<String, Object>> mergedFindings = aggregated.values().stream()
                .sorted(Comparator
                        .comparingInt(AggregatedFinding::consensusCount).reversed()
                        .thenComparingInt(AggregatedFinding::severityRank).reversed()
                        .thenComparingInt(AggregatedFinding::averageConfidence).reversed())
                .map(this::toMergedFindingMap)
                .toList();
        int averageScore = (int) Math.round(reviews.stream()
                .map(ModelReview::result)
                .mapToInt(QualityEvaluationStrategy.EvaluationResult::score)
                .average()
                .orElse(100));
        long consensusFindingCount = aggregated.values().stream()
                .filter(item -> item.consensusCount() > 1)
                .count();
        boolean hasIssue = !mergedFindings.isEmpty()
                || reviews.stream().map(ModelReview::result).anyMatch(QualityEvaluationStrategy.EvaluationResult::hasIssue);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("score", averageScore);
        result.put("hasIssue", hasIssue);
        result.put("summary", buildMergedSummary(reviews.size(), mergedFindings.size(), consensusFindingCount, averageScore));
        result.put("consensusFindingCount", consensusFindingCount);
        result.put("uniqueFindingCount", mergedFindings.size());
        result.put("reviewerCount", reviews.size());
        result.put("findings", mergedFindings);
        return result;
    }

    private String buildMergedSummary(int reviewerCount, int uniqueFindingCount, long consensusFindingCount, int averageScore) {
        long standaloneFindingCount = Math.max(0, uniqueFindingCount - consensusFindingCount);
        return reviewerCount + " 个模型完成交叉审查，共识问题 " + consensusFindingCount
                + " 项，独立问题 " + standaloneFindingCount
                + " 项，综合评分 " + averageScore;
    }

    private Map<String, Object> buildSummaryMap(List<ModelReview> reviews,
                                                Map<String, Object> mergedResult,
                                                QualityReviewPolicy reviewPolicy) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("modelCount", reviews.size());
        summary.put("overview", mergedResult.get("summary"));
        summary.put("completedAt", LocalDateTime.now().toString());
        summary.put("enabledDimensions", reviewPolicy == null ? List.of() : reviewPolicy.enabledDimensions());
        summary.put("ruleCount", reviewPolicy == null ? 0 : reviewPolicy.rules().size());
        summary.put("reviewers", reviews.stream().map(review -> {
            Map<String, Object> reviewerMap = new LinkedHashMap<>();
            reviewerMap.put("modelId", review.modelId());
            reviewerMap.put("score", review.result().score());
            reviewerMap.put("findingCount", review.result().findings().size());
            reviewerMap.put("summary", review.result().summary());
            return reviewerMap;
        }).toList());
        return summary;
    }

    private Map<String, Object> toMergedFindingMap(AggregatedFinding finding) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleCode", finding.ruleCode());
        result.put("type", finding.type());
        result.put("severity", finding.severity());
        result.put("confidence", finding.averageConfidence());
        result.put("title", finding.title());
        result.put("description", finding.description());
        result.put("location", finding.location());
        result.put("suggestion", finding.suggestion());
        result.put("consensusCount", finding.consensusCount());
        result.put("modelIds", List.copyOf(finding.modelIds()));
        return result;
    }

    private String buildFindingKey(QualityEvaluationStrategy.Finding finding) {
        String title = normalizeKeyPart(finding.title());
        String location = normalizeKeyPart(finding.location());
        if (!StringUtils.hasText(title) && !StringUtils.hasText(location)) {
            return finding.type() + "::" + normalizeKeyPart(finding.description());
        }
        return finding.type() + "::" + title + "::" + location;
    }

    private String normalizeKeyPart(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private List<String> normalizeModelIds(List<String> modelIds) {
        if (modelIds == null || modelIds.isEmpty()) {
            return List.of();
        }
        List<String> normalizedModelIds = modelIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
        if (normalizedModelIds.size() > MAX_REVIEWER_COUNT) {
            log.warn("交叉审查当前仅支持最多 {} 个模型，已截断多余配置: requested={}, retained={}",
                    MAX_REVIEWER_COUNT, normalizedModelIds, normalizedModelIds.subList(0, MAX_REVIEWER_COUNT));
            return normalizedModelIds.subList(0, MAX_REVIEWER_COUNT);
        }
        return normalizedModelIds;
    }

    private String resolveReferenceContent(String issueType, String specId) {
        if (!StringUtils.hasText(specId)) {
            return null;
        }
        if ("intent_defect".equalsIgnoreCase(issueType)) {
            return null;
        }
        return loadSpecContent(specId);
    }

    private String resolvePolicyTriggerMode(String issueType, String sourceType) {
        if ("manual".equalsIgnoreCase(sourceType)) {
            return "manual";
        }
        if ("intent_defect".equalsIgnoreCase(issueType)) {
            return "event";
        }
        return "agent_execution";
    }

    private String resolveTenantId(String tenantId, QualityProfile profile, String specId) {
        if (StringUtils.hasText(tenantId)) {
            return tenantId;
        }
        if (profile != null && StringUtils.hasText(profile.getTenantId())) {
            return profile.getTenantId();
        }
        if (StringUtils.hasText(specId)) {
            Spec spec = specMapper.selectById(specId);
            if (spec != null && StringUtils.hasText(spec.getTenantId())) {
                return spec.getTenantId();
            }
        }
        return SecurityUtil.getCurrentTenantId();
    }

    private String resolveFailureMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        if (StringUtils.hasText(current.getMessage())) {
            return current.getMessage();
        }
        return current.getClass().getSimpleName();
    }

    private String loadSpecContent(String specId) {
        if (!StringUtils.hasText(specId)) {
            return null;
        }
        List<SpecDocument> documents = specDocumentMapper.selectList(new LambdaQueryWrapper<SpecDocument>()
                .eq(SpecDocument::getSpecId, specId)
                .orderByAsc(SpecDocument::getDocType));
        StringBuilder builder = new StringBuilder();
        for (SpecDocument document : documents) {
            builder.append("## ")
                    .append(document.getDocType())
                    .append("\n")
                    .append(document.getContent() == null ? "" : document.getContent())
                    .append("\n\n");
        }
        return compactReviewContent(builder.toString().trim());
    }

    private String compactReviewContent(String content) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        String trimmed = content.trim();
        int compactThreshold = REVIEW_CONTENT_HEAD_CHARS + REVIEW_CONTENT_TAIL_CHARS + 256;
        if (trimmed.length() <= compactThreshold) {
            return trimmed;
        }
        int omittedCount = trimmed.length() - REVIEW_CONTENT_HEAD_CHARS - REVIEW_CONTENT_TAIL_CHARS;
        String compacted = trimmed.substring(0, REVIEW_CONTENT_HEAD_CHARS)
                + "\n\n[... 中间内容已截断 " + omittedCount
                + " 字。请基于已保留的章节标题、关键约束、流程、风险与验收段落完成审查 ...]\n\n"
                + trimmed.substring(trimmed.length() - REVIEW_CONTENT_TAIL_CHARS);
        if (compacted.length() <= REVIEW_CONTENT_MAX_TOTAL_CHARS) {
            return compacted;
        }
        int totalHead = REVIEW_CONTENT_MAX_TOTAL_CHARS * 2 / 3;
        int totalTail = REVIEW_CONTENT_MAX_TOTAL_CHARS - totalHead - 96;
        int totalOmittedCount = compacted.length() - totalHead - totalTail;
        return compacted.substring(0, totalHead)
                + "\n\n[... 交叉审查输入整体已进一步压缩 " + totalOmittedCount + " 字 ...]\n\n"
                + compacted.substring(compacted.length() - Math.max(totalTail, 1));
    }

    private record ModelReview(String modelId, QualityEvaluationStrategy.EvaluationResult result) {
    }

    private static final class AggregatedFinding {
        private final String ruleCode;
        private final String type;
        private String severity;
        private String title;
        private String description;
        private String location;
        private String suggestion;
        private int confidenceSum;
        private int confidenceCount;
        private final LinkedHashSet<String> modelIds = new LinkedHashSet<>();

        private AggregatedFinding(QualityEvaluationStrategy.Finding finding) {
            this.ruleCode = finding.ruleCode();
            this.type = finding.type();
            this.severity = finding.severity();
            this.title = finding.title();
            this.description = finding.description();
            this.location = finding.location();
            this.suggestion = finding.suggestion();
            this.confidenceSum = 0;
            this.confidenceCount = 0;
        }

        private AggregatedFinding merge(String modelId, QualityEvaluationStrategy.Finding finding) {
            this.modelIds.add(modelId);
            this.confidenceSum += finding.confidence();
            this.confidenceCount += 1;
            if (severityRank(finding.severity()) > severityRank(this.severity)) {
                this.severity = finding.severity();
            }
            if (finding.description() != null && finding.description().length() > this.description.length()) {
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
            return this;
        }

        private String ruleCode() {
            return ruleCode;
        }

        private String type() {
            return type;
        }

        private String severity() {
            return severity;
        }

        private String title() {
            return title;
        }

        private String description() {
            return description;
        }

        private String location() {
            return location;
        }

        private String suggestion() {
            return suggestion;
        }

        private int averageConfidence() {
            return confidenceCount == 0 ? 0 : confidenceSum / confidenceCount;
        }

        private int consensusCount() {
            return modelIds.size();
        }

        private List<String> modelIds() {
            return List.copyOf(modelIds);
        }

        private int severityRank() {
            return severityRank(severity);
        }

        private int severityRank(String severity) {
            return switch (severity == null ? "" : severity.toLowerCase(Locale.ROOT)) {
                case "critical" -> 3;
                case "warning" -> 2;
                case "info" -> 1;
                default -> 0;
            };
        }
    }
}
