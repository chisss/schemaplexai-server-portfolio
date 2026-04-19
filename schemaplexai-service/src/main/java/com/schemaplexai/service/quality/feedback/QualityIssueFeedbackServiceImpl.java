package com.schemaplexai.service.quality.feedback;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.enums.DeviationStatusEnum;
import com.schemaplexai.common.enums.GateDecisionEnum;
import com.schemaplexai.common.enums.QualityIssueTypeEnum;
import com.schemaplexai.dao.mapper.QualityIssueMapper;
import com.schemaplexai.model.entity.QualityIssue;
import com.schemaplexai.service.notification.InAppMessageService;
import com.schemaplexai.service.quality.pipeline.QualityCheckRequest;
import com.schemaplexai.service.quality.pipeline.QualityCheckResult;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy;
import com.schemaplexai.service.workflow.engine.WorkflowNodeEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质量问题反馈服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityIssueFeedbackServiceImpl implements QualityIssueFeedbackService {

    private final QualityIssueMapper qualityIssueMapper;
    private final InAppMessageService inAppMessageService;
    private final ObjectProvider<WorkflowNodeEngine> workflowNodeEngineProvider;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityIssue createIssue(QualityCheckResult result, QualityCheckRequest request,
                                    String workflowInstanceId, String workflowNodeId) {
        QualityIssue issue = new QualityIssue();
        issue.setTenantId(request.tenantId());
        issue.setSpecId(request.specId());
        issue.setTaskId(result.taskId());
        issue.setIssueType(request.issueType());
        issue.setSourceType(request.sourceType());
        issue.setSeverity(resolveSeverity(result));
        issue.setGateDecision(result.gateDecision());
        issue.setExecutionStrategy(request.executionStrategy());
        issue.setTitle(buildTitle(result, request));
        issue.setSummary(result.summary());
        issue.setScore(result.score());
        issue.setFindingCount(result.findings().size());
        issue.setFindingsJson(convertFindings(result.findings()));
        issue.setWorkflowInstanceId(workflowInstanceId);
        issue.setWorkflowNodeId(workflowNodeId);
        issue.setCrossReviewId(result.crossReviewId());
        issue.setStatus(DeviationStatusEnum.OPEN.getCode());
        issue.setCreatedAt(LocalDateTime.now());
        issue.setUpdatedAt(LocalDateTime.now());
        qualityIssueMapper.insert(issue);
        log.info("创建质量问题: id={}, issueType={}, severity={}, gateDecision={}",
                issue.getId(), issue.getIssueType(), issue.getSeverity(), issue.getGateDecision());
        return issue;
    }

    @Override
    public void sendNotification(QualityIssue issue) {
        if (issue == null || !StringUtils.hasText(issue.getTenantId())) {
            return;
        }
        String title = GateDecisionEnum.PAUSE.getCode().equals(issue.getGateDecision())
                ? "质量问题需要处理 - 工作流已暂停"
                : "发现质量问题";
        String content = "【" + issue.getTitle() + "】" + (StringUtils.hasText(issue.getSummary())
                ? issue.getSummary() : "请查看详情");
        String messageType = GateDecisionEnum.PAUSE.getCode().equals(issue.getGateDecision())
                || GateDecisionEnum.FAIL.getCode().equals(issue.getGateDecision())
                ? "todo" : "alert";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("qualityIssueId", issue.getId());
        payload.put("gateDecision", issue.getGateDecision());
        payload.put("severity", issue.getSeverity());
        if (StringUtils.hasText(issue.getWorkflowInstanceId())) {
            payload.put("workflowInstanceId", issue.getWorkflowInstanceId());
        }
        try {
            inAppMessageService.createMessage(
                    issue.getTenantId(), title, content, messageType,
                    "quality_issue_" + issue.getSeverity(),
                    "quality_issue", issue.getId(),
                    "/quality/issues/" + issue.getId(),
                    payload, List.of()
            );
        } catch (Exception e) {
            log.warn("发送质量问题通知失败: issueId={}, error={}", issue.getId(), e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void acknowledgeIssue(String issueId, String userId) {
        QualityIssue issue = qualityIssueMapper.selectById(issueId);
        if (issue == null) {
            throw new IllegalArgumentException("质量问题不存在: " + issueId);
        }
        issue.setStatus(DeviationStatusEnum.ACKNOWLEDGED.getCode());
        issue.setAssigneeId(userId);
        issue.setUpdatedBy(userId);
        issue.setUpdatedAt(LocalDateTime.now());
        qualityIssueMapper.updateById(issue);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignIssue(String issueId, String assigneeId, String operatorId) {
        QualityIssue issue = qualityIssueMapper.selectById(issueId);
        if (issue == null) {
            throw new IllegalArgumentException("质量问题不存在: " + issueId);
        }
        if (DeviationStatusEnum.RESOLVED.getCode().equals(issue.getStatus())
                || DeviationStatusEnum.IGNORED.getCode().equals(issue.getStatus())) {
            throw new IllegalStateException("当前质量问题已结束，无法继续指派");
        }
        issue.setAssigneeId(assigneeId);
        if (!StringUtils.hasText(issue.getStatus()) || "open".equals(issue.getStatus())) {
            issue.setStatus(DeviationStatusEnum.ACKNOWLEDGED.getCode());
        }
        issue.setUpdatedBy(operatorId);
        issue.setUpdatedAt(LocalDateTime.now());
        qualityIssueMapper.updateById(issue);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resolveIssue(String issueId, String action, String remark, String userId) {
        QualityIssue issue = qualityIssueMapper.selectById(issueId);
        if (issue == null) {
            throw new IllegalArgumentException("质量问题不存在: " + issueId);
        }
        issue.setStatus(DeviationStatusEnum.RESOLVED.getCode());
        issue.setResolutionAction(action);
        issue.setResolutionRemark(remark);
        issue.setResolvedBy(userId);
        issue.setResolvedAt(LocalDateTime.now());
        issue.setUpdatedBy(userId);
        issue.setUpdatedAt(LocalDateTime.now());
        qualityIssueMapper.updateById(issue);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ignoreIssue(String issueId, String remark, String userId) {
        QualityIssue issue = qualityIssueMapper.selectById(issueId);
        if (issue == null) {
            throw new IllegalArgumentException("质量问题不存在: " + issueId);
        }
        issue.setStatus(DeviationStatusEnum.IGNORED.getCode());
        issue.setResolutionAction("ignore");
        issue.setResolutionRemark(remark);
        issue.setResolvedBy(userId);
        issue.setResolvedAt(LocalDateTime.now());
        issue.setUpdatedBy(userId);
        issue.setUpdatedAt(LocalDateTime.now());
        qualityIssueMapper.updateById(issue);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resumeBlockedWorkflow(String issueId, String userId) {
        QualityIssue issue = qualityIssueMapper.selectById(issueId);
        if (issue == null) {
            throw new IllegalArgumentException("质量问题不存在: " + issueId);
        }
        if (!GateDecisionEnum.PAUSE.getCode().equals(issue.getGateDecision())) {
            throw new IllegalStateException("仅暂停决策的质量问题支持恢复工作流");
        }
        if (!StringUtils.hasText(issue.getWorkflowInstanceId()) || !StringUtils.hasText(issue.getWorkflowNodeId())) {
            throw new IllegalStateException("未关联工作流实例或节点，无法恢复");
        }
        // 恢复工作流
        WorkflowNodeEngine engine = workflowNodeEngineProvider.getObject();
        Map<String, Object> overrideData = new LinkedHashMap<>();
        overrideData.put("qualityIssueId", issueId);
        overrideData.put("qualityOverride", true);
        overrideData.put("overrideBy", userId);
        overrideData.put("overrideAt", LocalDateTime.now().toString());
        engine.resumePausedWorkflow(issue.getWorkflowInstanceId(), issue.getWorkflowNodeId(), overrideData);
        // 标记问题为已解决
        issue.setStatus(DeviationStatusEnum.RESOLVED.getCode());
        issue.setResolutionAction("override");
        issue.setResolutionRemark("人工覆盖闸门决策，恢复工作流执行");
        issue.setResolvedBy(userId);
        issue.setResolvedAt(LocalDateTime.now());
        issue.setUpdatedBy(userId);
        issue.setUpdatedAt(LocalDateTime.now());
        qualityIssueMapper.updateById(issue);
        log.info("质量问题已覆盖，工作流已恢复: issueId={}, instanceId={}, nodeId={}",
                issueId, issue.getWorkflowInstanceId(), issue.getWorkflowNodeId());
    }

    @Override
    public Map<String, Object> pageIssues(String tenantId, String specId, String status,
                                           String severity, String issueType, int page, int size) {
        LambdaQueryWrapper<QualityIssue> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(QualityIssue::getDeleted, 0);
        if (StringUtils.hasText(tenantId)) {
            wrapper.eq(QualityIssue::getTenantId, tenantId);
        }
        if (StringUtils.hasText(specId)) {
            wrapper.eq(QualityIssue::getSpecId, specId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(QualityIssue::getStatus, status);
        }
        if (StringUtils.hasText(severity)) {
            wrapper.eq(QualityIssue::getSeverity, severity);
        }
        if (StringUtils.hasText(issueType)) {
            wrapper.eq(QualityIssue::getIssueType, issueType);
        }
        wrapper.orderByDesc(QualityIssue::getCreatedAt);
        Page<QualityIssue> pageResult = qualityIssueMapper.selectPage(new Page<>(page, size), wrapper);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", pageResult.getRecords());
        result.put("total", pageResult.getTotal());
        result.put("page", pageResult.getCurrent());
        result.put("size", pageResult.getSize());
        return result;
    }

    @Override
    public QualityIssue getIssueDetail(String issueId) {
        return qualityIssueMapper.selectById(issueId);
    }

    @Override
    public Map<String, Object> getStatistics(String tenantId, String specId) {
        LambdaQueryWrapper<QualityIssue> baseWrapper = new LambdaQueryWrapper<QualityIssue>()
                .eq(QualityIssue::getDeleted, 0);
        if (StringUtils.hasText(tenantId)) {
            baseWrapper.eq(QualityIssue::getTenantId, tenantId);
        }
        if (StringUtils.hasText(specId)) {
            baseWrapper.eq(QualityIssue::getSpecId, specId);
        }
        long total = qualityIssueMapper.selectCount(baseWrapper);
        long open = countByStatus(baseWrapper, tenantId, specId, DeviationStatusEnum.OPEN.getCode());
        long acknowledged = countByStatus(baseWrapper, tenantId, specId, DeviationStatusEnum.ACKNOWLEDGED.getCode());
        long resolved = countByStatus(baseWrapper, tenantId, specId, DeviationStatusEnum.RESOLVED.getCode());
        long ignored = countByStatus(baseWrapper, tenantId, specId, DeviationStatusEnum.IGNORED.getCode());
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("open", open);
        stats.put("acknowledged", acknowledged);
        stats.put("resolved", resolved);
        stats.put("ignored", ignored);
        return stats;
    }

    // ==================== 辅助方法 ====================

    /** 按状态独立构建 wrapper 统计数量，避免复用 baseWrapper 引起的非静态上下文错误 */
    private long countByStatus(LambdaQueryWrapper<QualityIssue> baseWrapper, String tenantId, String specId, String status) {
        LambdaQueryWrapper<QualityIssue> wrapper = new LambdaQueryWrapper<QualityIssue>()
                .eq(QualityIssue::getDeleted, 0)
                .eq(QualityIssue::getStatus, status);
        if (StringUtils.hasText(tenantId)) {
            wrapper.eq(QualityIssue::getTenantId, tenantId);
        }
        if (StringUtils.hasText(specId)) {
            wrapper.eq(QualityIssue::getSpecId, specId);
        }
        return qualityIssueMapper.selectCount(wrapper);
    }

    private String resolveSeverity(QualityCheckResult result) {
        if (result.findings().isEmpty()) {
            return "info";
        }
        boolean hasCritical = result.findings().stream()
                .anyMatch(f -> "critical".equalsIgnoreCase(f.severity()));
        return hasCritical ? "critical" : "warning";
    }

    private String buildTitle(QualityCheckResult result, QualityCheckRequest request) {
        String type = QualityIssueTypeEnum.DEVIATION.getCode().equals(request.issueType())
                ? QualityIssueTypeEnum.DEVIATION.getDescription()
                : QualityIssueTypeEnum.INTENT_DEFECT.getDescription();
        return type + " - 发现 " + result.findings().size() + " 个问题 (得分: " + result.score() + ")";
    }

    private List<Map<String, Object>> convertFindings(List<QualityEvaluationStrategy.Finding> findings) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (QualityEvaluationStrategy.Finding f : findings) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", f.type());
            map.put("severity", f.severity());
            map.put("confidence", f.confidence());
            map.put("ruleCode", f.ruleCode());
            map.put("title", f.title());
            map.put("description", f.description());
            map.put("location", f.location());
            map.put("suggestion", f.suggestion());
            result.add(map);
        }
        return result;
    }
}
