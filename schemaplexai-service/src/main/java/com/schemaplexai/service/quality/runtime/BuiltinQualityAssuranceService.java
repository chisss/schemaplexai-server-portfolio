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
import com.schemaplexai.model.entity.IntentDefect;
import com.schemaplexai.model.entity.QualityProfile;
import com.schemaplexai.model.entity.QualityDeviation;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.SpecDocument;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.service.quality.QualityCrossReviewExecutionService;
import com.schemaplexai.service.quality.QualityProfileResolverService;
import com.schemaplexai.service.quality.task.QualityTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final Set<String> PLACEHOLDER_KEYWORDS = Set.of(
            "TODO", "TBD", "待补充", "待完善", "占位", "骨架", "mock", "模拟"
    );
    private static final Set<String> VAGUE_KEYWORDS = Set.of(
            "尽快", "适当", "灵活", "相关", "若干", "等等", "后续考虑", "视情况"
    );

    private final SpecMapper specMapper;
    private final SpecDocumentMapper specDocumentMapper;
    private final AgentExecutionMapper agentExecutionMapper;
    private final QualityDeviationMapper qualityDeviationMapper;
    private final IntentDefectMapper intentDefectMapper;
    private final AgentConfigMapper agentConfigMapper;
    private final QualityTaskManager qualityTaskManager;
    private final QualityProfileResolverService qualityProfileResolverService;
    private final QualityCrossReviewExecutionService qualityCrossReviewExecutionService;

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
                instance.getSpecId(), instance.getTemplateId(), agentId, ISSUE_TYPE_DEVIATION
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
        QualityProfile profile = qualityProfileResolverService.resolveProfile(specId, spec.getWorkflowId(), null, ISSUE_TYPE_INTENT_DEFECT);
        return runIntentDetection(null, spec, docType, profile, SOURCE_TYPE_SYSTEM);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> handleDeviationMessage(String taskId, String specId, String agentExecutionId, String targetContent) {
        Spec spec = specMapper.selectById(specId);
        if (spec == null) {
            return Map.of("qualityTaskId", taskId, "qualityTaskStatus", "failed", "qualitySummary", "Spec不存在");
        }
        String resolvedContent = StringUtils.hasText(targetContent) ? targetContent : loadAgentExecutionResult(agentExecutionId);
        QualityProfile profile = qualityProfileResolverService.resolveProfile(specId, spec.getWorkflowId(), resolveAgentId(agentExecutionId), ISSUE_TYPE_DEVIATION);
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
        QualityProfile profile = qualityProfileResolverService.resolveProfile(specId, spec.getWorkflowId(), null, ISSUE_TYPE_INTENT_DEFECT);
        return runIntentDetection(taskId, spec, docType, profile, SOURCE_TYPE_MANUAL);
    }

    private Map<String, Object> runDeviationDetection(String providedTaskId,
                                                      Spec spec,
                                                      String workflowTemplateId,
                                                      String agentExecutionId,
                                                      String targetContent,
                                                      Map<String, Object> requestPayload,
                                                      String nodeId,
                                                      String nodeLabel,
                                                      QualityProfile profile,
                                                      String sourceType) {
        String builtinAgentId = resolveBuiltinQualityAgentId();
        String taskId = qualityTaskManager.createTask(
                providedTaskId,
                ISSUE_TYPE_DEVIATION,
                TRIGGER_MODE_AGENT_EXECUTION,
                sourceType,
                builtinAgentId,
                spec.getTenantId(),
                spec.getId(),
                workflowTemplateId,
                agentExecutionId,
                profile != null ? profile.getId() : null,
                profile != null ? profile.getCode() : null,
                requestPayload
        );

        try {
            qualityTaskManager.markRunning(taskId);
            List<DeviationDraft> drafts = buildDeviationDrafts(nodeId, nodeLabel, targetContent);
            LocalDateTime now = LocalDateTime.now();
            for (DeviationDraft draft : drafts) {
                QualityDeviation entity = new QualityDeviation();
                entity.setTenantId(spec.getTenantId());
                entity.setSpecId(spec.getId());
                entity.setTaskId(taskId);
                entity.setAgentExecutionId(agentExecutionId);
                entity.setDeviationType(draft.type());
                entity.setDimensionCode(draft.dimensionCode());
                entity.setRuleCode(draft.ruleCode());
                entity.setSourceType(sourceType);
                entity.setSourceAgentId(builtinAgentId);
                entity.setSeverity(draft.severity());
                entity.setTitle(draft.title());
                entity.setDescription(draft.description());
                entity.setExpectedValue(draft.expectedValue());
                entity.setActualValue(draft.actualValue());
                boolean autoResolved = !SOURCE_TYPE_MANUAL.equalsIgnoreCase(sourceType);
                entity.setStatus(autoResolved ? DeviationStatusEnum.RESOLVED.getCode() : DeviationStatusEnum.OPEN.getCode());
                if (autoResolved) {
                    entity.setResolvedBy(builtinAgentId);
                    entity.setResolvedAt(now);
                }
                entity.setCreatedBy(builtinAgentId);
                entity.setCreatedAt(now);
                entity.setUpdatedBy(builtinAgentId);
                entity.setUpdatedAt(now);
                qualityDeviationMapper.insert(entity);
            }

            long warningCount = drafts.stream()
                    .filter(draft -> !DeviationSeverityEnum.INFO.getCode().equals(draft.severity()))
                    .count();
            Map<String, Object> resultSummary = new HashMap<>();
            resultSummary.put("nodeId", nodeId);
            resultSummary.put("nodeLabel", nodeLabel);
            resultSummary.put("deviationCount", drafts.size());
            resultSummary.put("warningCount", warningCount);
            resultSummary.put("checkedAt", now.toString());
            if (profile != null) {
                resultSummary.put("profileId", profile.getId());
                resultSummary.put("profileCode", profile.getCode());
            }
            qualityTaskManager.markSucceeded(taskId, drafts.size(), drafts.size(), 0, resultSummary);
            List<String> modelIds = profile == null ? List.of() : qualityProfileResolverService.listModelIds(profile.getId());
            if (modelIds.size() > 1) {
                qualityCrossReviewExecutionService.createAndExecute(
                        spec.getId(), taskId, profile.getId(), ISSUE_TYPE_DEVIATION, modelIds,
                        sourceType, builtinAgentId, targetContent
                );
                resultSummary.put("crossReviewEnabled", true);
            }

            Map<String, Object> output = new HashMap<>();
            output.put("qualityTaskId", taskId);
            output.put("qualityTaskStatus", "succeeded");
            output.put("qualityCheckedAt", now.toString());
            output.put("qualityDeviationCount", drafts.size());
            output.put("qualitySummary", warningCount > 0
                    ? "系统内置质量保障已完成实时分析，发现 " + warningCount + " 项需要关注的问题"
                    : "系统内置质量保障已完成实时分析，当前节点输出通过基础检查");
            if (StringUtils.hasText(builtinAgentId)) {
                output.put("qualityAgentId", builtinAgentId);
            }
            return output;
        } catch (Exception e) {
            qualityTaskManager.markFailed(taskId, e.getMessage());
            log.error("执行系统内置偏离分析失败: specId={}, nodeId={}, agentExecutionId={}",
                    spec.getId(), nodeId, agentExecutionId, e);
            return Map.of(
                    "qualityTaskId", taskId,
                    "qualityTaskStatus", "failed",
                    "qualitySummary", "系统内置质量保障执行失败: " + e.getMessage()
            );
        }
    }

    private Map<String, Object> runIntentDetection(String providedTaskId,
                                                   Spec spec,
                                                   String docType,
                                                   QualityProfile profile,
                                                   String sourceType) {
        String builtinAgentId = resolveBuiltinQualityAgentId();
        Map<String, Object> requestPayload = new HashMap<>();
        requestPayload.put("docType", docType);
        requestPayload.put("source", "spec-submit");
        String taskId = qualityTaskManager.createTask(
                providedTaskId,
                ISSUE_TYPE_INTENT_DEFECT,
                TRIGGER_MODE_EVENT,
                sourceType,
                builtinAgentId,
                spec.getTenantId(),
                spec.getId(),
                spec.getWorkflowId(),
                null,
                profile != null ? profile.getId() : null,
                profile != null ? profile.getCode() : null,
                requestPayload
        );

        try {
            qualityTaskManager.markRunning(taskId);
            String content = loadDocumentContent(spec.getId(), docType);
            List<IntentDraft> drafts = buildIntentDrafts(docType, content);
            LocalDateTime now = LocalDateTime.now();
            for (IntentDraft draft : drafts) {
                IntentDefect entity = new IntentDefect();
                entity.setTenantId(spec.getTenantId());
                entity.setSpecId(spec.getId());
                entity.setDocType(docType);
                entity.setDefectType(draft.defectType());
                entity.setDimensionCode(draft.dimensionCode());
                entity.setRuleCode(draft.ruleCode());
                entity.setSourceType(SOURCE_TYPE_SYSTEM);
                entity.setSourceAgentId(builtinAgentId);
                entity.setSeverity(draft.severity());
                entity.setTitle(draft.title());
                entity.setDescription(draft.description());
                entity.setLocation(draft.location());
                entity.setSuggestion(draft.suggestion());
                entity.setStatus(IntentDefectStatusEnum.OPEN.getCode());
                entity.setCreatedBy(builtinAgentId);
                entity.setCreatedAt(now);
                entity.setUpdatedBy(builtinAgentId);
                entity.setUpdatedAt(now);
                intentDefectMapper.insert(entity);
            }

            long actionableCount = drafts.stream()
                    .filter(draft -> !DeviationSeverityEnum.INFO.getCode().equals(draft.severity()))
                    .count();
            Map<String, Object> resultSummary = new HashMap<>();
            resultSummary.put("docType", docType);
            resultSummary.put("defectCount", drafts.size());
            resultSummary.put("actionableCount", actionableCount);
            resultSummary.put("checkedAt", now.toString());
            if (profile != null) {
                resultSummary.put("profileId", profile.getId());
                resultSummary.put("profileCode", profile.getCode());
            }
            qualityTaskManager.markSucceeded(taskId, drafts.size(), drafts.size(), 0, resultSummary);
            List<String> modelIds = profile == null ? List.of() : qualityProfileResolverService.listModelIds(profile.getId());
            if (modelIds.size() > 1) {
                qualityCrossReviewExecutionService.createAndExecute(
                        spec.getId(), taskId, profile.getId(), ISSUE_TYPE_INTENT_DEFECT, modelIds,
                        sourceType, builtinAgentId, content
                );
            }

            Map<String, Object> output = new HashMap<>();
            output.put("qualityTaskId", taskId);
            output.put("qualityTaskStatus", "succeeded");
            output.put("qualityCheckedAt", now.toString());
            output.put("qualitySummary", actionableCount > 0
                    ? "审批前意图缺陷分析已完成，发现 " + actionableCount + " 项需要关注的问题"
                    : "审批前意图缺陷分析已完成，当前文档未发现明显缺陷");
            if (StringUtils.hasText(builtinAgentId)) {
                output.put("qualityAgentId", builtinAgentId);
            }
            return output;
        } catch (Exception e) {
            qualityTaskManager.markFailed(taskId, e.getMessage());
            log.error("执行系统内置意图缺陷分析失败: specId={}, docType={}", spec.getId(), docType, e);
            return Map.of(
                    "qualityTaskId", taskId,
                    "qualityTaskStatus", "failed",
                    "qualitySummary", "审批前意图缺陷分析失败: " + e.getMessage()
            );
        }
    }

    private List<DeviationDraft> buildDeviationDrafts(String nodeId, String nodeLabel, String targetContent) {
        List<DeviationDraft> drafts = new ArrayList<>();
        String content = targetContent == null ? "" : targetContent.trim();
        boolean structuredOutput = looksStructured(content);
        boolean shortButAcceptable = structuredOutput && content.length() >= 60;
        if (!StringUtils.hasText(content)) {
            drafts.add(new DeviationDraft(
                    "structural",
                    "structural",
                    "qa.agent.output.empty",
                    DeviationSeverityEnum.WARNING.getCode(),
                    "Agent 节点输出为空",
                    "节点执行完成后没有产出可供后续流程消费的内容，可能导致消息传递链路中断",
                    "节点需要返回可解析的文本或文档",
                    "当前输出为空"
            ));
        } else {
            Set<String> matchedPlaceholders = findMatchedKeywords(content, PLACEHOLDER_KEYWORDS);
            if (!matchedPlaceholders.isEmpty()) {
                drafts.add(new DeviationDraft(
                        "semantic",
                        "semantic",
                        "qa.agent.output.placeholder",
                        DeviationSeverityEnum.WARNING.getCode(),
                        "Agent 输出仍包含占位描述",
                        "节点产出中出现占位或待完善描述，说明交付内容可能还不完整",
                        "输出应为可直接交付的完整内容",
                        "命中关键词: " + String.join(", ", matchedPlaceholders)
                ));
            }
            if ("doc_gen".equalsIgnoreCase(nodeId) && !content.startsWith("#")) {
                drafts.add(new DeviationDraft(
                        "structural",
                        "structural",
                        "qa.agent.output.markdown",
                        DeviationSeverityEnum.WARNING.getCode(),
                        "技术文档缺少 Markdown 标题结构",
                        "文档生成节点产出未以 Markdown 标题开头，可能导致最终产物结构不稳定",
                        "技术文档应包含清晰的 Markdown 标题结构",
                        content.length() > 60 ? content.substring(0, 60) : content
                ));
            }
            boolean requiresStructuredOutput = StringUtils.hasText(nodeId)
                    && (nodeId.toLowerCase().contains("analysis")
                    || nodeId.toLowerCase().contains("design")
                    || nodeId.toLowerCase().contains("task")
                    || nodeId.toLowerCase().contains("doc"));
            if (content.length() < 80 && requiresStructuredOutput && !shortButAcceptable) {
                drafts.add(new DeviationDraft(
                        "semantic",
                        "semantic",
                        "qa.agent.output.too-short",
                        DeviationSeverityEnum.INFO.getCode(),
                        "Agent 输出内容较短",
                        "当前节点产出内容较短，建议结合上下文确认是否已覆盖预期结论",
                        "输出应覆盖节点任务的核心结论",
                        "输出长度=" + content.length()
                ));
            }
        }

        return drafts;
    }

    private List<IntentDraft> buildIntentDrafts(String docType, String content) {
        List<IntentDraft> drafts = new ArrayList<>();
        String normalized = content == null ? "" : content.trim();
        if (!StringUtils.hasText(normalized)) {
            drafts.add(new IntentDraft(
                    "omission",
                    "intent",
                    "qa.intent.empty",
                    DeviationSeverityEnum.CRITICAL.getCode(),
                    "审批文档内容为空",
                    "提交审批前未找到对应文档内容，当前 Spec 无法支撑后续评审或研发流程",
                    docType,
                    "请先补全文档内容后再提交审批"
            ));
            return drafts;
        }

        if (normalized.length() < 200) {
            drafts.add(new IntentDraft(
                    "omission",
                    "intent",
                    "qa.intent.too-short",
                    DeviationSeverityEnum.WARNING.getCode(),
                    "审批文档内容过短",
                    "文档内容过短，可能缺少约束、边界或验收信息",
                    docType,
                    "建议补充业务背景、范围、约束和验收标准"
            ));
        }

        Set<String> missingSections = findMissingSections(docType, normalized);
        if (!missingSections.isEmpty()) {
            drafts.add(new IntentDraft(
                    "omission",
                    "intent",
                    "qa.intent.missing-sections",
                    DeviationSeverityEnum.WARNING.getCode(),
                    "审批文档缺少关键章节",
                    "当前文档缺少一些关键章节，可能导致后续 Agent 对需求边界和验收标准理解不完整",
                    docType,
                    "建议补充章节: " + String.join("、", missingSections)
            ));
        }

        Set<String> vagueKeywords = findMatchedKeywords(normalized, VAGUE_KEYWORDS);
        if (!vagueKeywords.isEmpty()) {
            drafts.add(new IntentDraft(
                    "vagueness",
                    "intent",
                    "qa.intent.vague-words",
                    DeviationSeverityEnum.INFO.getCode(),
                    "审批文档存在模糊表达",
                    "文档中包含容易引发理解偏差的模糊表达，建议在审批前澄清",
                    docType,
                    "建议明确这些表达: " + String.join("、", vagueKeywords)
            ));
        }

        drafts.add(new IntentDraft(
                "ambiguity",
                "intent",
                "qa.intent.summary",
                drafts.isEmpty() ? DeviationSeverityEnum.INFO.getCode() : DeviationSeverityEnum.WARNING.getCode(),
                drafts.isEmpty() ? "审批前意图扫描通过" : "审批前意图扫描发现待关注项",
                drafts.isEmpty()
                        ? "系统内置质量保障 Agent 已完成审批前意图扫描，未发现明显缺陷。"
                        : "系统内置质量保障 Agent 已完成审批前意图扫描，建议先处理上述问题再进入后续流程。",
                docType,
                "请结合扫描结果确认文档是否需要补充"
        ));
        return drafts;
    }

    private String loadDocumentContent(String specId, String docType) {
        SpecDocument document = specDocumentMapper.selectOne(new LambdaQueryWrapper<SpecDocument>()
                .eq(SpecDocument::getSpecId, specId)
                .eq(SpecDocument::getDocType, docType)
                .last("LIMIT 1"));
        return document != null ? document.getContent() : null;
    }

    private String loadAgentExecutionResult(String agentExecutionId) {
        if (!StringUtils.hasText(agentExecutionId)) {
            return null;
        }
        AgentExecution execution = agentExecutionMapper.selectById(agentExecutionId);
        return execution != null ? execution.getOutputResult() : null;
    }

    private String resolveAgentId(String agentExecutionId) {
        if (!StringUtils.hasText(agentExecutionId)) {
            return null;
        }
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

    private Set<String> findMatchedKeywords(String content, Set<String> keywords) {
        Set<String> matched = new LinkedHashSet<>();
        String upperContent = content.toUpperCase();
        for (String keyword : keywords) {
            String target = keyword.toUpperCase();
            if (upperContent.contains(target)) {
                matched.add(keyword);
            }
        }
        return matched;
    }

    private boolean looksStructured(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        return content.startsWith("#")
                || content.startsWith("{")
                || content.startsWith("[")
                || content.contains("## ")
                || content.contains("- ")
                || content.contains("* ")
                || content.contains("1. ")
                || content.contains("```")
                || content.contains("|");
    }

    private Set<String> findMissingSections(String docType, String content) {
        List<String> expectedSections = switch (docType) {
            case "requirements" -> List.of("背景", "目标", "范围", "验收", "约束");
            case "design" -> List.of("架构", "模块", "接口", "数据", "异常");
            case "tasks" -> List.of("任务", "分工", "测试", "交付");
            default -> List.of("背景", "目标");
        };

        Set<String> missing = new LinkedHashSet<>();
        for (String expectedSection : expectedSections) {
            if (!content.contains(expectedSection)) {
                missing.add(expectedSection);
            }
        }
        return missing;
    }

    private record DeviationDraft(
            String type,
            String dimensionCode,
            String ruleCode,
            String severity,
            String title,
            String description,
            String expectedValue,
            String actualValue
    ) {
    }

    private record IntentDraft(
            String defectType,
            String dimensionCode,
            String ruleCode,
            String severity,
            String title,
            String description,
            String location,
            String suggestion
    ) {
    }
}
