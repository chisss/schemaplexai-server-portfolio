package com.schemaplexai.service.quality.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.DeviationSeverityEnum;
import com.schemaplexai.common.enums.DeviationStatusEnum;
import com.schemaplexai.common.enums.IntentDefectStatusEnum;
import com.schemaplexai.dao.mapper.AgentConfigMapper;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.IntentDefectMapper;
import com.schemaplexai.dao.mapper.QualityDeviationMapper;
import com.schemaplexai.dao.mapper.SpecDocumentMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.model.entity.AgentConfig;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.CrossReview;
import com.schemaplexai.model.entity.IntentDefect;
import com.schemaplexai.model.entity.QualityDeviation;
import com.schemaplexai.model.entity.QualityProfile;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.SpecDocument;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.service.quality.QualityCrossReviewExecutionService;
import com.schemaplexai.service.quality.QualityProfileResolverService;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategyRegistry;
import com.schemaplexai.service.quality.strategy.QualityReviewPolicy;
import com.schemaplexai.service.quality.strategy.QualityReviewPolicyService;
import com.schemaplexai.service.quality.task.QualityTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BuiltinQualityAssuranceService {

    private static final String BUILTIN_POSITION = "quality-assurance-center";
    private static final String ISSUE_TYPE_DEVIATION = "deviation";
    private static final String ISSUE_TYPE_INTENT_DEFECT = "intent_defect";
    private static final String SOURCE_TYPE_SYSTEM = "system";
    private static final String SOURCE_TYPE_WORKFLOW = "workflow";
    private static final String SOURCE_TYPE_MANUAL = "manual";
    private static final String TRIGGER_MODE_AGENT_EXECUTION = "agent_execution";
    private static final String TRIGGER_MODE_EVENT = "event";
    private static final String QUALITY_GATE_MODE_DISABLED = "disabled";
    private static final String QUALITY_GATE_MODE_WARN = "warn";
    private static final String QUALITY_GATE_MODE_PAUSE = "pause";
    private static final String QUALITY_GATE_MODE_FAIL = "fail";

    private final SpecMapper specMapper;
    private final SpecDocumentMapper specDocumentMapper;
    private final AgentExecutionMapper agentExecutionMapper;
    private final QualityDeviationMapper qualityDeviationMapper;
    private final IntentDefectMapper intentDefectMapper;
    private final AgentConfigMapper agentConfigMapper;
    private final QualityTaskManager qualityTaskManager;
    private final QualityProfileResolverService qualityProfileResolverService;
    private final QualityCrossReviewExecutionService qualityCrossReviewExecutionService;
    private final QualityEvaluationStrategyRegistry strategyRegistry;
    private final QualityReviewPolicyService reviewPolicyService;

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> analyzeAgentNode(WorkflowInstance instance, WorkflowNodeExecution nodeExec, String result) {
        if (instance == null || nodeExec == null || !StringUtils.hasText(instance.getSpecId())) {
            return Map.of();
        }
        Spec spec = new Spec();
        spec.setId(instance.getSpecId());
        spec.setTenantId(instance.getTenantId());
        String agentId = resolveAgentId(nodeExec.getAgentExecutionId());
        QualityProfile profile = qualityProfileResolverService.resolveProfile(
                instance.getSpecId(), instance.getTemplateId(), agentId, ISSUE_TYPE_DEVIATION, SOURCE_TYPE_WORKFLOW
        );
        Map<String, Object> requestPayload = new HashMap<>();
        requestPayload.put("instanceId", instance.getId());
        requestPayload.put("nodeId", nodeExec.getNodeId());
        requestPayload.put("nodeLabel", nodeExec.getNodeLabel());
        requestPayload.put("agentExecutionId", nodeExec.getAgentExecutionId());
        requestPayload.put("resultLength", result != null ? result.length() : 0);
        return runDeviationDetection(null, spec, instance.getTemplateId(), nodeExec.getAgentExecutionId(), result,
                requestPayload, nodeExec.getNodeId(), nodeExec.getNodeLabel(), profile, SOURCE_TYPE_WORKFLOW);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> analyzeIntentDefects(String specId, String docType) {
        Spec spec = specMapper.selectById(specId);
        if (spec == null) {
            return Map.of("qualityTaskStatus", "skipped", "qualitySummary", "Spec不存在，跳过意图缺陷分析");
        }
        QualityProfile profile = qualityProfileResolverService.resolveProfile(
                specId, spec.getWorkflowId(), null, ISSUE_TYPE_INTENT_DEFECT, SOURCE_TYPE_SYSTEM
        );
        return runIntentDetection(null, spec, docType, profile, SOURCE_TYPE_SYSTEM);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> handleDeviationMessage(String taskId, String specId, String agentExecutionId, String targetContent) {
        Spec spec = specMapper.selectById(specId);
        if (spec == null) {
            return Map.of("qualityTaskId", taskId, "qualityTaskStatus", "failed", "qualitySummary", "Spec不存在");
        }
        String resolvedContent = StringUtils.hasText(targetContent) ? targetContent : loadAgentExecutionResult(agentExecutionId);
        QualityProfile profile = qualityProfileResolverService.resolveProfile(
                specId, spec.getWorkflowId(), resolveAgentId(agentExecutionId), ISSUE_TYPE_DEVIATION, SOURCE_TYPE_MANUAL
        );
        Map<String, Object> requestPayload = new HashMap<>();
        requestPayload.put("agentExecutionId", agentExecutionId);
        requestPayload.put("source", "mq");
        return runDeviationDetection(taskId, spec, spec.getWorkflowId(), agentExecutionId, resolvedContent, requestPayload,
                "manual_quality_check", "质量保障中心偏离检测", profile, SOURCE_TYPE_MANUAL);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> handleIntentMessage(String taskId, String specId, String docType) {
        Spec spec = specMapper.selectById(specId);
        if (spec == null) {
            return Map.of("qualityTaskId", taskId, "qualityTaskStatus", "failed", "qualitySummary", "Spec不存在");
        }
        QualityProfile profile = qualityProfileResolverService.resolveProfile(
                specId, spec.getWorkflowId(), null, ISSUE_TYPE_INTENT_DEFECT, SOURCE_TYPE_MANUAL
        );
        return runIntentDetection(taskId, spec, docType, profile, SOURCE_TYPE_MANUAL);
    }

    private Map<String, Object> runDeviationDetection(String providedTaskId, Spec spec, String workflowTemplateId,
                                                      String agentExecutionId, String targetContent,
                                                      Map<String, Object> requestPayload, String nodeId,
                                                      String nodeLabel, QualityProfile profile, String sourceType) {
        String builtinAgentId = resolveBuiltinQualityAgentId();
        String taskId = qualityTaskManager.createTask(
                providedTaskId, ISSUE_TYPE_DEVIATION, TRIGGER_MODE_AGENT_EXECUTION, sourceType,
                builtinAgentId, spec.getTenantId(), spec.getId(), workflowTemplateId, agentExecutionId,
                profile != null ? profile.getId() : null, profile != null ? profile.getCode() : null, requestPayload
        );

        try {
            qualityTaskManager.markRunning(taskId);

            List<String> modelIds = profile == null ? List.of() : qualityProfileResolverService.listModelIds(profile.getId());
            String primaryModelId = modelIds.isEmpty() ? null : modelIds.getFirst();
            QualityEvaluationStrategy strategy = strategyRegistry.getStrategy(ISSUE_TYPE_DEVIATION);
            QualityReviewPolicy reviewPolicy = reviewPolicyService.resolvePolicy(
                    profile, ISSUE_TYPE_DEVIATION, resolvePolicyTriggerMode(ISSUE_TYPE_DEVIATION, sourceType)
            );
            if (strategy == null || !StringUtils.hasText(primaryModelId)) {
                Map<String, Object> skippedSummary = new HashMap<>();
                skippedSummary.put("nodeId", nodeId);
                skippedSummary.put("nodeLabel", nodeLabel);
                skippedSummary.put("skipped", true);
                skippedSummary.put("reason", "未配置可用质量评估模型");
                skippedSummary.put("checkedAt", LocalDateTime.now().toString());
                if (profile != null) {
                    skippedSummary.put("profileId", profile.getId());
                    skippedSummary.put("profileCode", profile.getCode());
                }
                qualityTaskManager.markSucceeded(taskId, 0, 0, 0, skippedSummary);
                return Map.of(
                        "qualityTaskId", taskId,
                        "qualityTaskStatus", "skipped",
                        "qualitySummary", "未配置可用质量评估模型，已跳过偏离检测"
                );
            }
            QualityEvaluationStrategy.EvaluationResult evalResult = strategy.evaluate(new QualityEvaluationStrategy.EvaluationContext(
                    spec.getId(),
                    ISSUE_TYPE_DEVIATION,
                    null,
                    nodeId,
                    nodeLabel,
                    targetContent,
                    loadSpecContext(spec.getId()),
                    primaryModelId,
                    null,
                    Map.of("sourceType", sourceType),
                    reviewPolicy
            ));

            CrossReview crossReview = null;
            if (profile != null && modelIds.size() > 1) {
                crossReview = triggerCrossReviewSafely(
                        spec.getId(), taskId, profile.getId(), ISSUE_TYPE_DEVIATION, modelIds,
                        sourceType, builtinAgentId, targetContent, spec.getTenantId()
                );
            }
            ReviewAggregation aggregation = aggregateReviewResult(evalResult, primaryModelId, crossReview);
            LocalDateTime now = LocalDateTime.now();
            for (QualityEvaluationStrategy.Finding finding : aggregation.findings()) {
                QualityDeviation entity = buildDeviation(spec, taskId, agentExecutionId, sourceType, builtinAgentId, finding, now);
                qualityDeviationMapper.insert(entity);
            }

            long warningCount = aggregation.findings().stream()
                    .filter(f -> !DeviationSeverityEnum.INFO.getCode().equals(f.severity()))
                    .count();

            Map<String, Object> resultSummary = new HashMap<>();
            resultSummary.put("nodeId", nodeId);
            resultSummary.put("nodeLabel", nodeLabel);
            resultSummary.put("deviationCount", aggregation.findings().size());
            resultSummary.put("warningCount", warningCount);
            resultSummary.put("score", aggregation.score());
            resultSummary.put("checkedAt", now.toString());
            if (profile != null) {
                resultSummary.put("profileId", profile.getId());
                resultSummary.put("profileCode", profile.getCode());
            }
            if (crossReview != null) {
                resultSummary.put("crossReviewEnabled", true);
                resultSummary.put("crossReviewId", crossReview.getId());
                resultSummary.put("crossReviewStatus", crossReview.getStatus());
                resultSummary.put("crossReviewScore", aggregation.crossReviewScore());
                resultSummary.put("crossReviewFindingCount", aggregation.crossReviewFindingCount());
                resultSummary.put("crossReviewOverview", aggregation.crossReviewOverview());
            }
            qualityTaskManager.markSucceeded(taskId, aggregation.findings().size(), aggregation.findings().size(), 0, resultSummary);

            Map<String, Object> output = new HashMap<>();
            output.put("qualityTaskId", taskId);
            output.put("qualityTaskStatus", "succeeded");
            output.put("qualityCheckedAt", now.toString());
            output.put("qualityDeviationCount", aggregation.findings().size());
            output.put("qualityWarningCount", Math.toIntExact(warningCount));
            output.put("qualityScore", aggregation.score());
            output.put("qualitySummary", aggregation.summary());
            if (StringUtils.hasText(builtinAgentId)) {
                output.put("qualityAgentId", builtinAgentId);
            }
            if (crossReview != null) {
                output.put("crossReviewId", crossReview.getId());
                output.put("crossReviewStatus", crossReview.getStatus());
            } else if (profile != null && modelIds.size() > 1) {
                output.put("crossReviewStatus", "failed");
            }
            return output;
        } catch (Exception e) {
            String failureMessage = resolveFailureMessage(e);
            qualityTaskManager.markFailed(taskId, failureMessage);
            log.error("执行偏离分析失败: specId={}, nodeId={}", spec.getId(), nodeId, e);
            return Map.of("qualityTaskId", taskId, "qualityTaskStatus", "failed",
                    "qualitySummary", "质量保障执行失败: " + failureMessage);
        }
    }

    private Map<String, Object> runIntentDetection(String providedTaskId, Spec spec, String docType,
                                                   QualityProfile profile, String sourceType) {
        String builtinAgentId = resolveBuiltinQualityAgentId();
        Map<String, Object> requestPayload = new HashMap<>();
        requestPayload.put("docType", docType);
        requestPayload.put("source", "spec-submit");
        String taskId = qualityTaskManager.createTask(
                providedTaskId, ISSUE_TYPE_INTENT_DEFECT, TRIGGER_MODE_EVENT, sourceType,
                builtinAgentId, spec.getTenantId(), spec.getId(), spec.getWorkflowId(), null,
                profile != null ? profile.getId() : null, profile != null ? profile.getCode() : null, requestPayload
        );

        try {
            qualityTaskManager.markRunning(taskId);
            String content = loadDocumentContent(spec.getId(), docType);

            List<String> modelIds = profile == null ? List.of() : qualityProfileResolverService.listModelIds(profile.getId());
            String primaryModelId = modelIds.isEmpty() ? null : modelIds.getFirst();

            QualityEvaluationStrategy strategy = strategyRegistry.getStrategy(ISSUE_TYPE_INTENT_DEFECT);
            QualityReviewPolicy reviewPolicy = reviewPolicyService.resolvePolicy(
                    profile, ISSUE_TYPE_INTENT_DEFECT, resolvePolicyTriggerMode(ISSUE_TYPE_INTENT_DEFECT, sourceType)
            );
            if (strategy == null || !StringUtils.hasText(primaryModelId)) {
                Map<String, Object> skippedSummary = new HashMap<>();
                skippedSummary.put("docType", docType);
                skippedSummary.put("skipped", true);
                skippedSummary.put("reason", "未配置可用质量评估模型");
                skippedSummary.put("checkedAt", LocalDateTime.now().toString());
                if (profile != null) {
                    skippedSummary.put("profileId", profile.getId());
                    skippedSummary.put("profileCode", profile.getCode());
                }
                qualityTaskManager.markSucceeded(taskId, 0, 0, 0, skippedSummary);
                return Map.of(
                        "qualityTaskId", taskId,
                        "qualityTaskStatus", "skipped",
                        "qualitySummary", "未配置可用质量评估模型，已跳过意图缺陷分析"
                );
            }
            QualityEvaluationStrategy.EvaluationResult evalResult = strategy.evaluate(new QualityEvaluationStrategy.EvaluationContext(
                    spec.getId(),
                    ISSUE_TYPE_INTENT_DEFECT,
                    docType,
                    null,
                    null,
                    content,
                    null,
                    primaryModelId,
                    null,
                    Map.of("sourceType", sourceType),
                    reviewPolicy
            ));

            CrossReview crossReview = null;
            if (profile != null && modelIds.size() > 1) {
                crossReview = triggerCrossReviewSafely(
                        spec.getId(), taskId, profile.getId(), ISSUE_TYPE_INTENT_DEFECT, modelIds,
                        sourceType, builtinAgentId, content, spec.getTenantId()
                );
            }
            ReviewAggregation aggregation = aggregateReviewResult(evalResult, primaryModelId, crossReview);
            LocalDateTime now = LocalDateTime.now();
            for (QualityEvaluationStrategy.Finding finding : aggregation.findings()) {
                IntentDefect entity = buildIntentDefect(spec, taskId, docType, sourceType, builtinAgentId, finding, now);
                intentDefectMapper.insert(entity);
            }

            long actionableCount = aggregation.findings().stream()
                    .filter(f -> !DeviationSeverityEnum.INFO.getCode().equals(f.severity()))
                    .count();

            Map<String, Object> resultSummary = new HashMap<>();
            resultSummary.put("docType", docType);
            resultSummary.put("defectCount", aggregation.findings().size());
            resultSummary.put("actionableCount", actionableCount);
            resultSummary.put("score", aggregation.score());
            resultSummary.put("checkedAt", now.toString());
            if (profile != null) {
                resultSummary.put("profileId", profile.getId());
                resultSummary.put("profileCode", profile.getCode());
            }
            if (crossReview != null) {
                resultSummary.put("crossReviewId", crossReview.getId());
                resultSummary.put("crossReviewStatus", crossReview.getStatus());
                resultSummary.put("crossReviewScore", aggregation.crossReviewScore());
                resultSummary.put("crossReviewFindingCount", aggregation.crossReviewFindingCount());
                resultSummary.put("crossReviewOverview", aggregation.crossReviewOverview());
            }
            qualityTaskManager.markSucceeded(taskId, aggregation.findings().size(), aggregation.findings().size(), 0, resultSummary);

            Map<String, Object> output = new HashMap<>();
            output.put("qualityTaskId", taskId);
            output.put("qualityTaskStatus", "succeeded");
            output.put("qualityCheckedAt", now.toString());
            output.put("qualityScore", aggregation.score());
            output.put("qualitySummary", aggregation.summary());
            output.put("qualityDefectCount", aggregation.findings().size());
            if (StringUtils.hasText(builtinAgentId)) {
                output.put("qualityAgentId", builtinAgentId);
            }
            if (crossReview != null) {
                output.put("crossReviewId", crossReview.getId());
                output.put("crossReviewStatus", crossReview.getStatus());
            } else if (profile != null && modelIds.size() > 1) {
                output.put("crossReviewStatus", "failed");
            }
            return output;
        } catch (Exception e) {
            String failureMessage = resolveFailureMessage(e);
            qualityTaskManager.markFailed(taskId, failureMessage);
            log.error("执行意图缺陷分析失败: specId={}, docType={}", spec.getId(), docType, e);
            return Map.of("qualityTaskId", taskId, "qualityTaskStatus", "failed",
                    "qualitySummary", "意图缺陷分析失败: " + failureMessage);
        }
    }

    private CrossReview triggerCrossReviewSafely(String specId,
                                                 String taskId,
                                                 String profileId,
                                                 String issueType,
                                                 List<String> modelIds,
                                                 String sourceType,
                                                 String sourceAgentId,
                                                 String targetContent,
                                                 String tenantId) {
        try {
            return qualityCrossReviewExecutionService.createAndExecute(
                    specId, taskId, profileId, issueType, modelIds, sourceType, sourceAgentId, targetContent, tenantId
            );
        } catch (Exception ex) {
            log.error("触发交叉审查失败: specId={}, taskId={}, issueType={}", specId, taskId, issueType, ex);
            return null;
        }
    }

    private QualityDeviation buildDeviation(Spec spec, String taskId, String agentExecutionId,
                                            String sourceType, String builtinAgentId,
                                            QualityEvaluationStrategy.Finding finding, LocalDateTime now) {
        QualityDeviation entity = new QualityDeviation();
        entity.setTenantId(spec.getTenantId());
        entity.setSpecId(spec.getId());
        entity.setTaskId(taskId);
        entity.setAgentExecutionId(agentExecutionId);
        entity.setDeviationType(finding.type());
        entity.setDimensionCode(finding.type());
        entity.setRuleCode(finding.ruleCode());
        entity.setSourceType(sourceType);
        entity.setSourceAgentId(builtinAgentId);
        entity.setSeverity(finding.severity());
        entity.setTitle(finding.title());
        entity.setDescription(finding.description());
        entity.setExpectedValue(finding.suggestion());
        entity.setActualValue(finding.location());
        entity.setStatus(DeviationStatusEnum.OPEN.getCode());
        entity.setCreatedBy(builtinAgentId);
        entity.setCreatedAt(now);
        entity.setUpdatedBy(builtinAgentId);
        entity.setUpdatedAt(now);
        return entity;
    }

    private IntentDefect buildIntentDefect(Spec spec, String taskId, String docType,
                                           String sourceType, String builtinAgentId,
                                           QualityEvaluationStrategy.Finding finding, LocalDateTime now) {
        IntentDefect entity = new IntentDefect();
        entity.setTenantId(spec.getTenantId());
        entity.setSpecId(spec.getId());
        entity.setDocType(docType);
        entity.setDefectType(finding.type());
        entity.setDimensionCode("intent");
        entity.setRuleCode(finding.ruleCode());
        entity.setSourceType(sourceType);
        entity.setSourceAgentId(builtinAgentId);
        entity.setSeverity(finding.severity());
        entity.setTitle(finding.title());
        entity.setDescription(finding.description());
        entity.setLocation(finding.location());
        entity.setSuggestion(finding.suggestion());
        entity.setStatus(IntentDefectStatusEnum.OPEN.getCode());
        entity.setCreatedBy(builtinAgentId);
        entity.setCreatedAt(now);
        entity.setUpdatedBy(builtinAgentId);
        entity.setUpdatedAt(now);
        return entity;
    }

    private String loadDocumentContent(String specId, String docType) {
        SpecDocument document = specDocumentMapper.selectOne(new LambdaQueryWrapper<SpecDocument>()
                .eq(SpecDocument::getSpecId, specId)
                .eq(SpecDocument::getDocType, docType)
                .last("LIMIT 1"));
        return document != null ? document.getContent() : null;
    }

    public WorkflowNodeQualityGateDecision evaluateWorkflowNodeGate(Map<String, Object> qualityResult,
                                                                     Map<String, Object> nodeConfig) {
        String gateMode = asText(nodeConfig == null ? null : nodeConfig.get("qualityGateMode"));
        if (!StringUtils.hasText(gateMode)) {
            gateMode = QUALITY_GATE_MODE_WARN;
        }
        if (QUALITY_GATE_MODE_DISABLED.equalsIgnoreCase(gateMode) || qualityResult == null || qualityResult.isEmpty()) {
            return WorkflowNodeQualityGateDecision.pass("未启用质量闸门");
        }

        boolean taskFailed = "failed".equalsIgnoreCase(asText(qualityResult.get("qualityTaskStatus")));
        Integer score = readInteger(qualityResult.get("qualityScore"), null);
        int deviationCount = readInteger(qualityResult.get("qualityDeviationCount"), 0);
        int warningCount = readInteger(qualityResult.get("qualityWarningCount"), deviationCount);
        Integer minQualityScore = readInteger(nodeConfig == null ? null : nodeConfig.get("minQualityScore"), null);
        Integer maxDeviationCount = readInteger(nodeConfig == null ? null : nodeConfig.get("maxDeviationCount"), null);
        Integer maxWarningCount = readInteger(nodeConfig == null ? null : nodeConfig.get("maxWarningCount"), null);
        boolean blockOnTaskFailed = Boolean.TRUE.equals(readBoolean(nodeConfig == null ? null : nodeConfig.get("blockOnQualityTaskFailed")));

        List<String> reasons = new ArrayList<>();
        if (taskFailed && blockOnTaskFailed) {
            reasons.add("质量任务执行失败");
        }
        if (minQualityScore != null && score != null && score < minQualityScore) {
            reasons.add("质量分低于阈值(" + score + "<" + minQualityScore + ")");
        }
        if (maxDeviationCount != null && deviationCount > maxDeviationCount) {
            reasons.add("偏离数量超限(" + deviationCount + ">" + maxDeviationCount + ")");
        }
        if (maxWarningCount != null && warningCount > maxWarningCount) {
            reasons.add("预警数量超限(" + warningCount + ">" + maxWarningCount + ")");
        }
        if (reasons.isEmpty()) {
            return WorkflowNodeQualityGateDecision.pass("质量闸门通过");
        }

        String summary = StringUtils.collectionToDelimitedString(reasons, "；");
        if (QUALITY_GATE_MODE_FAIL.equalsIgnoreCase(gateMode)) {
            return WorkflowNodeQualityGateDecision.fail(summary);
        }
        if (QUALITY_GATE_MODE_PAUSE.equalsIgnoreCase(gateMode)) {
            return WorkflowNodeQualityGateDecision.pause(summary);
        }
        return WorkflowNodeQualityGateDecision.warn(summary);
    }

    private String loadSpecContext(String specId) {
        if (!StringUtils.hasText(specId)) {
            return null;
        }
        List<SpecDocument> documents = specDocumentMapper.selectList(new LambdaQueryWrapper<SpecDocument>()
                .eq(SpecDocument::getSpecId, specId)
                .orderByAsc(SpecDocument::getDocType));
        if (documents.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (SpecDocument document : documents) {
            builder.append("## ")
                    .append(document.getDocType())
                    .append("\n")
                    .append(document.getContent() == null ? "" : document.getContent())
                    .append("\n\n");
        }
        return builder.toString().trim();
    }

    private String loadAgentExecutionResult(String agentExecutionId) {
        if (!StringUtils.hasText(agentExecutionId)) return null;
        AgentExecution execution = agentExecutionMapper.selectById(agentExecutionId);
        return execution != null ? execution.getOutputResult() : null;
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

    private String resolveAgentId(String agentExecutionId) {
        if (!StringUtils.hasText(agentExecutionId)) return null;
        AgentExecution execution = agentExecutionMapper.selectById(agentExecutionId);
        return execution == null ? null : execution.getAgentId();
    }

    private String resolveBuiltinQualityAgentId() {
        return agentConfigMapper.selectList(new LambdaQueryWrapper<AgentConfig>()
                        .eq(AgentConfig::getConfigKey, "builtin_position")
                        .eq(AgentConfig::getConfigValue, BUILTIN_POSITION))
                .stream()
                .map(AgentConfig::getAgentId)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private String resolvePolicyTriggerMode(String issueType, String sourceType) {
        if (SOURCE_TYPE_MANUAL.equalsIgnoreCase(sourceType)) {
            return SOURCE_TYPE_MANUAL;
        }
        if (ISSUE_TYPE_INTENT_DEFECT.equalsIgnoreCase(issueType)) {
            return TRIGGER_MODE_EVENT;
        }
        return TRIGGER_MODE_AGENT_EXECUTION;
    }

    private ReviewAggregation aggregateReviewResult(QualityEvaluationStrategy.EvaluationResult evalResult,
                                                    String primaryModelId,
                                                    CrossReview crossReview) {
        QualityEvaluationStrategy.EvaluationResult effectiveEvalResult =
                resolveEffectivePrimaryResult(evalResult, primaryModelId, crossReview);
        List<QualityEvaluationStrategy.Finding> mergedFindings = mergeFindings(
                effectiveEvalResult == null ? List.of() : effectiveEvalResult.findings(),
                extractCrossReviewFindings(crossReview)
        );
        int crossReviewScore = resolveCrossReviewScore(crossReview);
        int effectiveScore = effectiveEvalResult == null ? 100 : effectiveEvalResult.score();
        if (crossReviewScore >= 0) {
            effectiveScore = Math.min(effectiveScore, crossReviewScore);
        }
        String crossReviewOverview = resolveCrossReviewOverview(crossReview);
        String effectiveSummary = StringUtils.hasText(crossReviewOverview) && !mergedFindings.isEmpty()
                ? crossReviewOverview
                : effectiveEvalResult == null ? "未发现问题" : effectiveEvalResult.summary();
        return new ReviewAggregation(
                mergedFindings,
                effectiveScore,
                effectiveSummary,
                crossReviewScore,
                extractCrossReviewFindings(crossReview).size(),
                crossReviewOverview
        );
    }

    private QualityEvaluationStrategy.EvaluationResult resolveEffectivePrimaryResult(
            QualityEvaluationStrategy.EvaluationResult evalResult,
            String primaryModelId,
            CrossReview crossReview) {
        if (!isInfrastructureFailure(evalResult) || !StringUtils.hasText(primaryModelId) || crossReview == null) {
            return evalResult;
        }
        QualityEvaluationStrategy.EvaluationResult reviewerResult = extractReviewerResult(crossReview, primaryModelId);
        if (reviewerResult == null || isInfrastructureFailure(reviewerResult)) {
            return evalResult;
        }
        log.warn("主检测结果为基础设施失败，已回退为交叉审核中的同模型结果: reviewId={}, modelId={}",
                crossReview.getId(), primaryModelId);
        return reviewerResult;
    }

    private List<QualityEvaluationStrategy.Finding> extractCrossReviewFindings(CrossReview crossReview) {
        if (crossReview == null || crossReview.getMergedResult() == null) {
            return List.of();
        }
        Object findingsObj = crossReview.getMergedResult().get("findings");
        if (!(findingsObj instanceof List<?> findingList) || findingList.isEmpty()) {
            return List.of();
        }
        List<QualityEvaluationStrategy.Finding> findings = new ArrayList<>();
        for (Object findingObj : findingList) {
            if (!(findingObj instanceof Map<?, ?> findingMap)) {
                continue;
            }
            findings.add(new QualityEvaluationStrategy.Finding(
                    asText(findingMap.get("type")),
                    asText(findingMap.get("severity")),
                    toInt(findingMap.get("confidence"), 0),
                    asText(findingMap.get("ruleCode")),
                    asText(findingMap.get("title")),
                    asText(findingMap.get("description")),
                    asText(findingMap.get("location")),
                    asText(findingMap.get("suggestion"))
            ));
        }
        return findings;
    }

    private QualityEvaluationStrategy.EvaluationResult extractReviewerResult(CrossReview crossReview, String modelId) {
        if (crossReview == null || !StringUtils.hasText(modelId)) {
            return null;
        }
        QualityEvaluationStrategy.EvaluationResult modelAResult =
                toEvaluationResult(crossReview.getModelAResult(), crossReview.getModelAId(), modelId);
        if (modelAResult != null) {
            return modelAResult;
        }
        return toEvaluationResult(crossReview.getModelBResult(), crossReview.getModelBId(), modelId);
    }

    private QualityEvaluationStrategy.EvaluationResult toEvaluationResult(Map<String, Object> resultMap,
                                                                          String reviewerModelId,
                                                                          String expectedModelId) {
        if (resultMap == null || !StringUtils.hasText(reviewerModelId) || !reviewerModelId.equals(expectedModelId)) {
            return null;
        }
        Object findingsObj = resultMap.get("findings");
        List<QualityEvaluationStrategy.Finding> findings = new ArrayList<>();
        if (findingsObj instanceof List<?> findingList) {
            for (Object findingObj : findingList) {
                if (!(findingObj instanceof Map<?, ?> findingMap)) {
                    continue;
                }
                findings.add(new QualityEvaluationStrategy.Finding(
                        asText(findingMap.get("type")),
                        asText(findingMap.get("severity")),
                        toInt(findingMap.get("confidence"), 0),
                        asText(findingMap.get("ruleCode")),
                        asText(findingMap.get("title")),
                        asText(findingMap.get("description")),
                        asText(findingMap.get("location")),
                        asText(findingMap.get("suggestion"))
                ));
            }
        }
        return new QualityEvaluationStrategy.EvaluationResult(
                Boolean.TRUE.equals(resultMap.get("hasIssue")),
                toInt(resultMap.get("score"), 100),
                asText(resultMap.get("summary")),
                List.copyOf(findings)
        );
    }

    private List<QualityEvaluationStrategy.Finding> mergeFindings(List<QualityEvaluationStrategy.Finding> primaryFindings,
                                                                  List<QualityEvaluationStrategy.Finding> secondaryFindings) {
        Map<String, QualityEvaluationStrategy.Finding> merged = new LinkedHashMap<>();
        for (QualityEvaluationStrategy.Finding finding : primaryFindings == null ? List.<QualityEvaluationStrategy.Finding>of() : primaryFindings) {
            merged.put(buildFindingKey(finding), finding);
        }
        for (QualityEvaluationStrategy.Finding finding : secondaryFindings == null ? List.<QualityEvaluationStrategy.Finding>of() : secondaryFindings) {
            merged.merge(buildFindingKey(finding), finding, this::mergeFinding);
        }
        return List.copyOf(merged.values());
    }

    private QualityEvaluationStrategy.Finding mergeFinding(QualityEvaluationStrategy.Finding current,
                                                           QualityEvaluationStrategy.Finding incoming) {
        if (current == null) {
            return incoming;
        }
        if (incoming == null) {
            return current;
        }
        return new QualityEvaluationStrategy.Finding(
                StringUtils.hasText(current.type()) ? current.type() : incoming.type(),
                severityRank(incoming.severity()) > severityRank(current.severity()) ? incoming.severity() : current.severity(),
                Math.max(current.confidence(), incoming.confidence()),
                StringUtils.hasText(current.ruleCode()) ? current.ruleCode() : incoming.ruleCode(),
                preferText(current.title(), incoming.title()),
                preferLongerText(current.description(), incoming.description()),
                preferText(current.location(), incoming.location()),
                preferLongerText(current.suggestion(), incoming.suggestion())
        );
    }

    private String buildFindingKey(QualityEvaluationStrategy.Finding finding) {
        if (finding == null) {
            return "";
        }
        String title = normalizeKeyPart(finding.title());
        String location = normalizeKeyPart(finding.location());
        if (!StringUtils.hasText(title) && !StringUtils.hasText(location)) {
            return normalizeKeyPart(finding.type()) + "::" + normalizeKeyPart(finding.description());
        }
        return normalizeKeyPart(finding.type()) + "::" + title + "::" + location;
    }

    private String normalizeKeyPart(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private int resolveCrossReviewScore(CrossReview crossReview) {
        if (crossReview == null || crossReview.getMergedResult() == null) {
            return -1;
        }
        return toInt(crossReview.getMergedResult().get("score"), -1);
    }

    private boolean isInfrastructureFailure(QualityEvaluationStrategy.EvaluationResult evalResult) {
        if (evalResult == null || evalResult.findings() == null || evalResult.findings().isEmpty()) {
            return false;
        }
        return evalResult.findings().stream()
                .map(QualityEvaluationStrategy.Finding::ruleCode)
                .filter(StringUtils::hasText)
                .allMatch(ruleCode -> ruleCode.startsWith("qa.infrastructure."));
    }

    private String resolveCrossReviewOverview(CrossReview crossReview) {
        if (crossReview == null || crossReview.getSummary() == null) {
            return null;
        }
        return asText(crossReview.getSummary().get("overview"));
    }

    private Integer readInteger(Object value, Integer defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private Boolean readBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String normalized = String.valueOf(value).trim().toLowerCase();
        return switch (normalized) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> null;
        };
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private int severityRank(String severity) {
        return switch (severity == null ? "" : severity.toLowerCase()) {
            case "critical" -> 3;
            case "warning" -> 2;
            case "info" -> 1;
            default -> 0;
        };
    }

    private String preferText(String current, String incoming) {
        return StringUtils.hasText(current) ? current : incoming;
    }

    private String preferLongerText(String current, String incoming) {
        if (!StringUtils.hasText(current)) {
            return incoming;
        }
        if (!StringUtils.hasText(incoming)) {
            return current;
        }
        return incoming.length() > current.length() ? incoming : current;
    }

    private record ReviewAggregation(List<QualityEvaluationStrategy.Finding> findings,
                                     int score,
                                     String summary,
                                     int crossReviewScore,
                                     int crossReviewFindingCount,
                                     String crossReviewOverview) {
    }

    public record WorkflowNodeQualityGateDecision(String decision, String message, boolean pauseWorkflow,
                                                  boolean failWorkflow) {

        public static WorkflowNodeQualityGateDecision pass(String message) {
            return new WorkflowNodeQualityGateDecision("pass", message, false, false);
        }

        public static WorkflowNodeQualityGateDecision warn(String message) {
            return new WorkflowNodeQualityGateDecision("warn", message, false, false);
        }

        public static WorkflowNodeQualityGateDecision pause(String message) {
            return new WorkflowNodeQualityGateDecision("pause", message, true, false);
        }

        public static WorkflowNodeQualityGateDecision fail(String message) {
            return new WorkflowNodeQualityGateDecision("fail", message, false, true);
        }

        public Map<String, Object> toOutputData() {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("qualityGateDecision", decision);
            output.put("qualityGateMessage", message);
            output.put("qualityGateTriggered", !"pass".equalsIgnoreCase(decision));
            output.put("qualityGatePauseWorkflow", pauseWorkflow);
            output.put("qualityGateFailWorkflow", failWorkflow);
            return output;
        }
    }
}
