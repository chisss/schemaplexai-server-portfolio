package com.schemaplexai.service.quality.task;

import com.schemaplexai.common.enums.TaskStatusEnum;
import com.schemaplexai.dao.mapper.QualityTaskMapper;
import com.schemaplexai.model.entity.QualityTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
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
}
