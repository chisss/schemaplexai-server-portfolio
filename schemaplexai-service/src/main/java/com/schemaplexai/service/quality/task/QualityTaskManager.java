package com.schemaplexai.service.quality.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.enums.DeviationSeverityEnum;
import com.schemaplexai.common.enums.DeviationStatusEnum;
import com.schemaplexai.common.enums.DeviationTypeEnum;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.enums.TaskStatusEnum;
import com.schemaplexai.dao.mapper.QualityDeviationMapper;
import com.schemaplexai.dao.mapper.QualityTaskMapper;
import com.schemaplexai.model.entity.QualityDeviation;
import com.schemaplexai.model.entity.QualityTask;
import com.schemaplexai.service.quality.pipeline.QualityCheckRequest;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy.Finding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 质量任务管理器
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityTaskManager {

    private final QualityTaskMapper qualityTaskMapper;
    private final QualityDeviationMapper deviationMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public String createTask(String taskId,
                             String issueType,
                             String triggerMode,
                             String sourceType,
                             String sourceAgentId,
                             String tenantId,
                             String specId,
                             String workflowTemplateId,
                             String agentExecutionId,
                             String profileId,
                             String profileCode,
                             Map<String, Object> requestPayload) {
        QualityTask task = new QualityTask();
        task.setId(StringUtils.hasText(taskId) ? taskId : UUID.randomUUID().toString());
        task.setTaskNo("QT-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8));
        task.setTenantId(tenantId);
        task.setIssueType(issueType);
        task.setTriggerMode(triggerMode);
        task.setSourceType(sourceType);
        task.setSourceAgentId(sourceAgentId);
        task.setSpecId(specId);
        task.setWorkflowTemplateId(workflowTemplateId);
        task.setAgentExecutionId(agentExecutionId);
        task.setProfileId(profileId);
        task.setProfileCode(profileCode);
        task.setStatus(TaskStatusEnum.PENDING.getCode());
        task.setProgress(0);
        task.setTotalItems(0);
        task.setSuccessItems(0);
        task.setFailedItems(0);
        task.setSkippedItems(0);
        task.setRequestPayload(requestPayload);
        task.setCreatedBy(sourceAgentId);
        task.setUpdatedBy(sourceAgentId);
        qualityTaskMapper.insert(task);
        log.info("创建质量任务: taskId={}, taskNo={}", task.getId(), task.getTaskNo());
        return task.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markRunning(String taskId) {
        QualityTask patch = new QualityTask();
        patch.setId(taskId);
        patch.setStatus(TaskStatusEnum.RUNNING.getCode());
        patch.setProgress(5);
        patch.setStartedAt(LocalDateTime.now());
        qualityTaskMapper.updateById(patch);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markSucceeded(String taskId, int totalItems, int successItems, int failedItems, Map<String, Object> resultSummary) {
        updateSucceeded(taskId, totalItems, successItems, failedItems, resultSummary);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void completeManualDeviationTask(String taskId, QualityCheckRequest request,
                                            List<Finding> findings, Map<String, Object> resultSummary) {
        for (Finding finding : findings) {
            var deviation = new QualityDeviation();
            deviation.setTenantId(request.tenantId());
            deviation.setTaskId(taskId);
            deviation.setSpecId(request.specId());
            deviation.setAgentExecutionId(request.agentExecutionId());
            deviation.setSourceType(request.sourceType());
            deviation.setStatus(DeviationStatusEnum.OPEN.getCode());
            deviation.setDeviationType(resolveDeviationType(finding.type()));
            deviation.setSeverity(resolveSeverity(finding.severity()));
            deviation.setRuleCode(limit(finding.ruleCode(), 100));
            deviation.setTitle(limit(StringUtils.hasText(finding.title()) ? finding.title() : "检测到偏离", 200));
            deviation.setDescription(buildDescription(finding));
            deviationMapper.insert(deviation);
        }
        updateSucceeded(taskId, findings.size(), findings.size(), 0, resultSummary);
    }

    private void updateSucceeded(String taskId, int totalItems, int successItems, int failedItems,
                                 Map<String, Object> resultSummary) {
        QualityTask patch = new QualityTask();
        patch.setId(taskId);
        patch.setStatus(TaskStatusEnum.SUCCEEDED.getCode());
        patch.setProgress(100);
        patch.setTotalItems(totalItems);
        patch.setSuccessItems(successItems);
        patch.setFailedItems(failedItems);
        patch.setSkippedItems(Math.max(0, totalItems - successItems - failedItems));
        patch.setResultSummary(resultSummary);
        patch.setCompletedAt(LocalDateTime.now());
        qualityTaskMapper.updateById(patch);
    }

    private String resolveDeviationType(String type) {
        return switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case "structural", "placeholder", "missing_structure", "too_short" -> DeviationTypeEnum.STRUCTURAL.getCode();
            case "performance" -> DeviationTypeEnum.PERFORMANCE.getCode();
            case "security" -> DeviationTypeEnum.SECURITY.getCode();
            default -> DeviationTypeEnum.SEMANTIC.getCode();
        };
    }

    private String resolveSeverity(String severity) {
        return switch (severity == null ? "" : severity.toLowerCase(Locale.ROOT)) {
            case "critical" -> DeviationSeverityEnum.CRITICAL.getCode();
            case "info" -> DeviationSeverityEnum.INFO.getCode();
            default -> DeviationSeverityEnum.WARNING.getCode();
        };
    }

    private String buildDescription(Finding finding) {
        var lines = new StringBuilder();
        if (StringUtils.hasText(finding.description())) {
            lines.append(finding.description());
        }
        if (StringUtils.hasText(finding.location())) {
            lines.append("\n位置：").append(finding.location());
        }
        if (StringUtils.hasText(finding.suggestion())) {
            lines.append("\n建议：").append(finding.suggestion());
        }
        return lines.toString().strip();
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.codePointCount(0, value.length()) <= maxLength) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxLength));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markFailed(String taskId, String errorMessage) {
        QualityTask patch = new QualityTask();
        patch.setId(taskId);
        patch.setStatus(TaskStatusEnum.FAILED.getCode());
        patch.setProgress(100);
        patch.setErrorMessage(errorMessage);
        patch.setCompletedAt(LocalDateTime.now());
        qualityTaskMapper.updateById(patch);
    }

    public String getStatus(String taskId) {
        QualityTask task = qualityTaskMapper.selectById(taskId);
        return task == null ? "unknown" : task.getStatus();
    }

    public PageResult<QualityTask> pageTasks(String tenantId, Integer page, Integer size) {
        long current = page == null || page < 1 ? 1L : page;
        long pageSize = size == null || size < 1 ? 20L : size;
        Page<QualityTask> pageParam = new Page<>(current, pageSize);
        LambdaQueryWrapper<QualityTask> wrapper = new LambdaQueryWrapper<QualityTask>()
                .eq(StringUtils.hasText(tenantId), QualityTask::getTenantId, tenantId)
                .orderByDesc(QualityTask::getCreatedAt);
        Page<QualityTask> result = qualityTaskMapper.selectPage(pageParam, wrapper);
        return new PageResult<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }
}
