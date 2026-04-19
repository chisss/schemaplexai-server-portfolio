package com.schemaplexai.service.quality.feedback;

import com.schemaplexai.model.entity.QualityIssue;
import com.schemaplexai.service.quality.pipeline.QualityCheckRequest;
import com.schemaplexai.service.quality.pipeline.QualityCheckResult;

import java.util.List;
import java.util.Map;

/**
 * 质量问题反馈服务
 * 实现闸门决策后的通知→待办→处理→恢复闭环
 */
public interface QualityIssueFeedbackService {

    /**
     * 创建质量问题（闸门决策为 warn/pause/fail 时调用）
     */
    QualityIssue createIssue(QualityCheckResult result, QualityCheckRequest request,
                             String workflowInstanceId, String workflowNodeId);

    /**
     * 发送通知（站内信 + 外部渠道）
     */
    void sendNotification(QualityIssue issue);

    /**
     * 确认问题
     */
    void acknowledgeIssue(String issueId, String userId);

    /**
     * 指派问题处理人
     */
    void assignIssue(String issueId, String assigneeId, String operatorId);

    /**
     * 解决问题
     */
    void resolveIssue(String issueId, String action, String remark, String userId);

    /**
     * 忽略问题
     */
    void ignoreIssue(String issueId, String remark, String userId);

    /**
     * 恢复被阻断的工作流
     */
    void resumeBlockedWorkflow(String issueId, String userId);

    /**
     * 分页查询
     */
    Map<String, Object> pageIssues(String tenantId, String specId, String status,
                                    String severity, String issueType, int page, int size);

    /**
     * 问题详情
     */
    QualityIssue getIssueDetail(String issueId);

    /**
     * 问题统计
     */
    Map<String, Object> getStatistics(String tenantId, String specId);
}
