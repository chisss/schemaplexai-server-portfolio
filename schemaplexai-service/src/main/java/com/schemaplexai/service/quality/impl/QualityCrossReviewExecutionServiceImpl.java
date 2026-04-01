package com.schemaplexai.service.quality.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.TaskStatusEnum;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.CrossReviewMapper;
import com.schemaplexai.dao.mapper.SpecDocumentMapper;
import com.schemaplexai.model.entity.CrossReview;
import com.schemaplexai.model.entity.SpecDocument;
import com.schemaplexai.service.quality.QualityCrossReviewExecutionService;
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
import java.util.concurrent.CompletableFuture;

/**
 * 质量交叉审查执行服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityCrossReviewExecutionServiceImpl implements QualityCrossReviewExecutionService {

    private final CrossReviewMapper crossReviewMapper;
    private final SpecDocumentMapper specDocumentMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CrossReview createAndExecute(String specId,
                                        String taskId,
                                        String profileId,
                                        String issueType,
                                        List<String> modelIds,
                                        String sourceType,
                                        String sourceAgentId,
                                        String targetContent) {
        if (modelIds == null || modelIds.isEmpty()) {
            return null;
        }
        CrossReview entity = new CrossReview();
        entity.setTenantId(SecurityUtil.getCurrentTenantId());
        entity.setSpecId(specId);
        entity.setTaskId(taskId);
        entity.setProfileId(profileId);
        entity.setIssueType(issueType);
        entity.setModelAId(modelIds.get(0));
        entity.setModelBId(modelIds.size() > 1 ? modelIds.get(1) : null);
        entity.setSourceType(sourceType);
        entity.setSourceAgentId(sourceAgentId);
        entity.setStatus(TaskStatusEnum.RUNNING.getCode());
        entity.setStartedAt(LocalDateTime.now());
        entity.setCreatedBy(StringUtils.hasText(sourceAgentId) ? sourceAgentId : SecurityUtil.getCurrentUserId());
        crossReviewMapper.insert(entity);

        try {
            String content = StringUtils.hasText(targetContent) ? targetContent : loadSpecContent(specId);
            CompletableFuture<Map<String, Object>> primaryFuture = CompletableFuture.supplyAsync(
                    () -> buildModelResult(modelIds.get(0), issueType, content)
            );
            CompletableFuture<Map<String, Object>> reviewerFuture = modelIds.size() > 1
                    ? CompletableFuture.supplyAsync(() -> buildModelResult(modelIds.get(1), issueType, content))
                    : CompletableFuture.completedFuture(null);

            Map<String, Object> modelAResult = primaryFuture.join();
            Map<String, Object> modelBResult = reviewerFuture.join();

            CrossReview patch = new CrossReview();
            patch.setId(entity.getId());
            patch.setModelAResult(modelAResult);
            patch.setModelBResult(modelBResult);
            patch.setMergedResult(buildMergedResult(modelAResult, modelBResult));
            patch.setSummary(buildSummary(modelAResult, modelBResult));
            patch.setStatus(TaskStatusEnum.SUCCEEDED.getCode());
            patch.setCompletedAt(LocalDateTime.now());
            crossReviewMapper.updateById(patch);
            return crossReviewMapper.selectById(entity.getId());
        } catch (Exception ex) {
            log.error("执行交叉审查失败: specId={}, profileId={}", specId, profileId, ex);
            CrossReview patch = new CrossReview();
            patch.setId(entity.getId());
            patch.setStatus(TaskStatusEnum.FAILED.getCode());
            patch.setErrorMessage(ex.getMessage());
            patch.setCompletedAt(LocalDateTime.now());
            crossReviewMapper.updateById(patch);
            return crossReviewMapper.selectById(entity.getId());
        }
    }

    private String loadSpecContent(String specId) {
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
        return builder.toString();
    }

    private Map<String, Object> buildModelResult(String modelId, String issueType, String content) {
        List<Map<String, Object>> findings = new ArrayList<>();
        String normalized = content == null ? "" : content.trim();
        if (!StringUtils.hasText(normalized)) {
            findings.add(Map.of(
                    "type", "omission",
                    "severity", "critical",
                    "title", "待审内容为空",
                    "description", "没有可供模型评审的上下文内容"
            ));
        }
        if (normalized.length() < 180) {
            findings.add(Map.of(
                    "type", "omission",
                    "severity", "warning",
                    "title", "内容长度偏短",
                    "description", "建议补充边界、约束和验收信息"
            ));
        }
        if (normalized.contains("TODO") || normalized.contains("待补充") || normalized.contains("占位")) {
            findings.add(Map.of(
                    "type", "ambiguity",
                    "severity", "warning",
                    "title", "存在占位内容",
                    "description", "发现待补充或占位表达，建议在评审前补全"
            ));
        }
        if (normalized.contains("适当") || normalized.contains("尽快") || normalized.contains("灵活")) {
            findings.add(Map.of(
                    "type", "vagueness",
                    "severity", "info",
                    "title", "存在模糊措辞",
                    "description", "建议将模糊描述细化为可执行约束"
            ));
        }

        Set<String> types = new LinkedHashSet<>();
        findings.forEach(item -> types.add(String.valueOf(item.get("type"))));
        Map<String, Object> result = new HashMap<>();
        result.put("modelId", modelId);
        result.put("issueType", issueType);
        result.put("score", Math.max(0, 100 - findings.size() * 12));
        result.put("summary", findings.isEmpty() ? "未发现明显问题" : "发现 " + findings.size() + " 项需要关注的内容");
        result.put("findingTypes", types);
        result.put("findings", findings);
        return result;
    }

    private Map<String, Object> buildMergedResult(Map<String, Object> modelAResult, Map<String, Object> modelBResult) {
        if (modelBResult == null) {
            return modelAResult;
        }
        List<Map<String, Object>> mergedFindings = new ArrayList<>();
        List<Map<String, Object>> findingsA = castFindings(modelAResult.get("findings"));
        List<Map<String, Object>> findingsB = castFindings(modelBResult.get("findings"));
        mergedFindings.addAll(findingsA);
        for (Map<String, Object> item : findingsB) {
            boolean exists = findingsA.stream().anyMatch(origin ->
                    String.valueOf(origin.get("title")).equals(String.valueOf(item.get("title"))));
            if (!exists) {
                mergedFindings.add(item);
            }
        }
        Map<String, Object> merged = new HashMap<>();
        merged.put("summary", "双模型并行审查完成，合并发现 " + mergedFindings.size() + " 项结果");
        merged.put("findings", mergedFindings);
        merged.put("score", ((int) modelAResult.get("score") + (int) modelBResult.get("score")) / 2);
        return merged;
    }

    private Map<String, Object> buildSummary(Map<String, Object> modelAResult, Map<String, Object> modelBResult) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("modelCount", modelBResult == null ? 1 : 2);
        summary.put("findingCount", castFindings(modelAResult.get("findings")).size()
                + (modelBResult == null ? 0 : castFindings(modelBResult.get("findings")).size()));
        summary.put("completedAt", LocalDateTime.now().toString());
        return summary;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castFindings(Object findings) {
        return findings instanceof List<?> list
                ? list.stream().map(item -> (Map<String, Object>) item).toList()
                : List.of();
    }
}
