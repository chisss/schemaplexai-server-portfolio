package com.schemaplexai.service.approval.impl;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.QualityIssueMapper;
import com.schemaplexai.dao.mapper.ReviewSessionMapper;
import com.schemaplexai.dao.mapper.SecurityIncidentActionMapper;
import com.schemaplexai.dao.mapper.SecurityIncidentMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.UserMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.model.dto.approval.ApprovalCenterBatchAssignRequest;
import com.schemaplexai.model.dto.approval.ApprovalCenterBatchReviewRequest;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.dto.approval.ApprovalCenterQueryRequest;
import com.schemaplexai.model.entity.QualityIssue;
import com.schemaplexai.model.entity.ReviewSession;
import com.schemaplexai.model.entity.SecurityIncident;
import com.schemaplexai.model.entity.SecurityIncidentAction;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.User;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.model.vo.approval.ApprovalCenterBatchActionResultVO;
import com.schemaplexai.model.vo.approval.ApprovalCenterItemVO;
import com.schemaplexai.service.quality.feedback.QualityIssueFeedbackService;
import com.schemaplexai.service.security.SecurityIncidentService;
import com.schemaplexai.service.workflow.ReviewSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalCenterServiceImplTest {

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
    }

    @Test
    void shouldReturnPendingApprovalItemsWithUnifiedAssigneeAndActionUrl() {
        ReviewSessionMapper reviewSessionMapper = mock(ReviewSessionMapper.class);
        QualityIssueMapper qualityIssueMapper = mock(QualityIssueMapper.class);
        SecurityIncidentMapper securityIncidentMapper = mock(SecurityIncidentMapper.class);
        SecurityIncidentActionMapper securityIncidentActionMapper = mock(SecurityIncidentActionMapper.class);
        SpecMapper specMapper = mock(SpecMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        WorkflowNodeExecutionMapper workflowNodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        ReviewSessionService reviewSessionService = mock(ReviewSessionService.class);
        QualityIssueFeedbackService qualityIssueFeedbackService = mock(QualityIssueFeedbackService.class);
        SecurityIncidentService securityIncidentService = mock(SecurityIncidentService.class);

        ApprovalCenterServiceImpl service = new ApprovalCenterServiceImpl(
                reviewSessionMapper,
                qualityIssueMapper,
                securityIncidentMapper,
                securityIncidentActionMapper,
                specMapper,
                userMapper,
                workflowNodeExecutionMapper,
                reviewSessionService,
                qualityIssueFeedbackService,
                securityIncidentService
        );

        SecurityUtil.setCurrentTenantId("tenant-1");

        LocalDateTime now = LocalDateTime.of(2026, 4, 16, 10, 0, 0);
        when(reviewSessionMapper.selectList(any())).thenReturn(List.of(buildPendingReviewSession(now.minusHours(2))));
        when(qualityIssueMapper.selectList(any())).thenReturn(List.of(buildResolvedQualityIssue(now.minusDays(1))));
        when(securityIncidentMapper.selectList(any())).thenReturn(List.of(buildPendingSecurityIncident(now.minusHours(1))));
        when(securityIncidentActionMapper.selectList(any())).thenReturn(List.of(buildSecurityAction(now.minusMinutes(30))));
        when(specMapper.selectBatchIds(any())).thenReturn(List.of(buildSpec()));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
                buildUser("owner-1", "产品负责人"),
                buildUser("reviewer-1", "架构师A"),
                buildUser("qa-1", "质量负责人"),
                buildUser("qa-lead-1", "质量审批人"),
                buildUser("sec-1", "安全值班人")
        ));
        when(workflowNodeExecutionMapper.selectOne(any())).thenReturn(buildNodeExecution());

        PageResult<ApprovalCenterItemVO> result = service.page(new ApprovalCenterQueryRequest());

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getRecords()).extracting(ApprovalCenterItemVO::getApprovalType)
                .containsExactly("security_incident", "workflow_review");

        ApprovalCenterItemVO reviewItem = result.getRecords().stream()
                .filter(item -> "workflow_review".equals(item.getApprovalType()))
                .findFirst()
                .orElseThrow();
        assertThat(reviewItem.getTitle()).isEqualTo("统一审批 Spec / 方案审批");
        assertThat(reviewItem.getSummary()).isEqualTo("共 1 位审批人");
        assertThat(reviewItem.getAssigneeName()).isEqualTo("架构师A");
        assertThat(reviewItem.getActionUrl()).isEqualTo("/approval-center?type=workflow_review&id=session-1");
        assertThat(reviewItem.getPending()).isTrue();
        assertThat(reviewItem.getApproverName()).isNull();

        ApprovalCenterItemVO securityItem = result.getRecords().stream()
                .filter(item -> "security_incident".equals(item.getApprovalType()))
                .findFirst()
                .orElseThrow();
        assertThat(securityItem.getAssigneeName()).isEqualTo("安全值班人");
        assertThat(securityItem.getApproverName()).isEqualTo("安全主管");
        assertThat(securityItem.getPending()).isTrue();
    }

    @Test
    void shouldReturnProcessedWorkflowReviewWithApproverName() {
        ReviewSessionMapper reviewSessionMapper = mock(ReviewSessionMapper.class);
        QualityIssueMapper qualityIssueMapper = mock(QualityIssueMapper.class);
        SecurityIncidentMapper securityIncidentMapper = mock(SecurityIncidentMapper.class);
        SecurityIncidentActionMapper securityIncidentActionMapper = mock(SecurityIncidentActionMapper.class);
        SpecMapper specMapper = mock(SpecMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        WorkflowNodeExecutionMapper workflowNodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        ReviewSessionService reviewSessionService = mock(ReviewSessionService.class);
        QualityIssueFeedbackService qualityIssueFeedbackService = mock(QualityIssueFeedbackService.class);
        SecurityIncidentService securityIncidentService = mock(SecurityIncidentService.class);

        ApprovalCenterServiceImpl service = new ApprovalCenterServiceImpl(
                reviewSessionMapper,
                qualityIssueMapper,
                securityIncidentMapper,
                securityIncidentActionMapper,
                specMapper,
                userMapper,
                workflowNodeExecutionMapper,
                reviewSessionService,
                qualityIssueFeedbackService,
                securityIncidentService
        );

        SecurityUtil.setCurrentTenantId("tenant-1");

        LocalDateTime approvedAt = LocalDateTime.of(2026, 4, 16, 9, 30, 0);
        when(reviewSessionMapper.selectList(any())).thenReturn(List.of(buildApprovedReviewSession(approvedAt)));
        when(qualityIssueMapper.selectList(any())).thenReturn(List.of());
        when(securityIncidentMapper.selectList(any())).thenReturn(List.of());
        when(specMapper.selectBatchIds(any())).thenReturn(List.of(buildSpec()));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
                buildUser("owner-1", "产品负责人"),
                buildUser("reviewer-1", "架构师A")
        ));
        when(workflowNodeExecutionMapper.selectOne(any())).thenReturn(buildNodeExecution());

        ApprovalCenterQueryRequest request = new ApprovalCenterQueryRequest();
        request.setView("processed");

        PageResult<ApprovalCenterItemVO> result = service.page(request);

        assertThat(result.getTotal()).isEqualTo(1);
        ApprovalCenterItemVO item = result.getRecords().get(0);
        assertThat(item.getApprovalType()).isEqualTo("workflow_review");
        assertThat(item.getPending()).isFalse();
        assertThat(item.getStatus()).isEqualTo("approved");
        assertThat(item.getStatusLabel()).isEqualTo("已通过");
        assertThat(item.getApproverId()).isEqualTo("reviewer-1");
        assertThat(item.getApproverName()).isEqualTo("架构师A");
        assertThat(item.getApproverAt()).isEqualTo(approvedAt);
    }

    @Test
    void shouldIgnoreNullUserIdsWhenAggregatingQualityAndSecurityItems() {
        ReviewSessionMapper reviewSessionMapper = mock(ReviewSessionMapper.class);
        QualityIssueMapper qualityIssueMapper = mock(QualityIssueMapper.class);
        SecurityIncidentMapper securityIncidentMapper = mock(SecurityIncidentMapper.class);
        SecurityIncidentActionMapper securityIncidentActionMapper = mock(SecurityIncidentActionMapper.class);
        SpecMapper specMapper = mock(SpecMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        WorkflowNodeExecutionMapper workflowNodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        ReviewSessionService reviewSessionService = mock(ReviewSessionService.class);
        QualityIssueFeedbackService qualityIssueFeedbackService = mock(QualityIssueFeedbackService.class);
        SecurityIncidentService securityIncidentService = mock(SecurityIncidentService.class);

        ApprovalCenterServiceImpl service = new ApprovalCenterServiceImpl(
                reviewSessionMapper,
                qualityIssueMapper,
                securityIncidentMapper,
                securityIncidentActionMapper,
                specMapper,
                userMapper,
                workflowNodeExecutionMapper,
                reviewSessionService,
                qualityIssueFeedbackService,
                securityIncidentService
        );

        SecurityUtil.setCurrentTenantId("tenant-1");

        QualityIssue qualityIssue = new QualityIssue();
        qualityIssue.setId("quality-null-1");
        qualityIssue.setTenantId("tenant-1");
        qualityIssue.setSpecId("spec-1");
        qualityIssue.setTitle("缺少指派人的质量问题");
        qualityIssue.setGateDecision("fail");
        qualityIssue.setStatus("open");
        qualityIssue.setCreatedBy("owner-1");
        qualityIssue.setCreatedAt(LocalDateTime.of(2026, 4, 16, 8, 0, 0));
        qualityIssue.setUpdatedAt(LocalDateTime.of(2026, 4, 16, 8, 30, 0));

        SecurityIncident incident = new SecurityIncident();
        incident.setId("incident-null-1");
        incident.setTenantId("tenant-1");
        incident.setEventTitle("缺少负责人的安全事件");
        incident.setStatus("new");
        incident.setCreatedBy("owner-1");
        incident.setCreatedAt(LocalDateTime.of(2026, 4, 16, 9, 0, 0));
        incident.setUpdatedAt(LocalDateTime.of(2026, 4, 16, 9, 30, 0));

        when(reviewSessionMapper.selectList(any())).thenReturn(List.of());
        when(qualityIssueMapper.selectList(any())).thenReturn(List.of(qualityIssue));
        when(securityIncidentMapper.selectList(any())).thenReturn(List.of(incident));
        when(securityIncidentActionMapper.selectList(any())).thenReturn(List.of());
        when(specMapper.selectBatchIds(any())).thenReturn(List.of(buildSpec()));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(buildUser("owner-1", "产品负责人")));

        ApprovalCenterQueryRequest request = new ApprovalCenterQueryRequest();
        request.setView("all");

        PageResult<ApprovalCenterItemVO> result = service.page(request);

        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getRecords()).extracting(ApprovalCenterItemVO::getApprovalType)
                .containsExactly("security_incident", "quality_issue");
        assertThat(result.getRecords()).allSatisfy(item ->
                assertThat(item.getApplicantName()).isEqualTo("产品负责人"));
    }

    @Test
    void shouldOnlyExposeBlockingQualityIssuesInApprovalCenter() {
        ReviewSessionMapper reviewSessionMapper = mock(ReviewSessionMapper.class);
        QualityIssueMapper qualityIssueMapper = mock(QualityIssueMapper.class);
        SecurityIncidentMapper securityIncidentMapper = mock(SecurityIncidentMapper.class);
        SecurityIncidentActionMapper securityIncidentActionMapper = mock(SecurityIncidentActionMapper.class);
        SpecMapper specMapper = mock(SpecMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        WorkflowNodeExecutionMapper workflowNodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        ReviewSessionService reviewSessionService = mock(ReviewSessionService.class);
        QualityIssueFeedbackService qualityIssueFeedbackService = mock(QualityIssueFeedbackService.class);
        SecurityIncidentService securityIncidentService = mock(SecurityIncidentService.class);

        ApprovalCenterServiceImpl service = new ApprovalCenterServiceImpl(
                reviewSessionMapper,
                qualityIssueMapper,
                securityIncidentMapper,
                securityIncidentActionMapper,
                specMapper,
                userMapper,
                workflowNodeExecutionMapper,
                reviewSessionService,
                qualityIssueFeedbackService,
                securityIncidentService
        );

        SecurityUtil.setCurrentTenantId("tenant-1");

        QualityIssue blockingIssue = new QualityIssue();
        blockingIssue.setId("quality-blocking-1");
        blockingIssue.setTenantId("tenant-1");
        blockingIssue.setTitle("阻断级质量问题");
        blockingIssue.setGateDecision("fail");
        blockingIssue.setStatus("open");
        blockingIssue.setUpdatedAt(LocalDateTime.of(2026, 4, 16, 10, 0, 0));

        QualityIssue resolvedIssue = new QualityIssue();
        resolvedIssue.setId("quality-resolved-1");
        resolvedIssue.setTenantId("tenant-1");
        resolvedIssue.setTitle("已解决质量问题");
        resolvedIssue.setGateDecision("fail");
        resolvedIssue.setStatus("resolved");
        resolvedIssue.setUpdatedAt(LocalDateTime.of(2026, 4, 16, 9, 0, 0));

        QualityIssue warnIssue = new QualityIssue();
        warnIssue.setId("quality-warn-1");
        warnIssue.setTenantId("tenant-1");
        warnIssue.setTitle("告警级质量问题");
        warnIssue.setGateDecision("warn");
        warnIssue.setStatus("open");
        warnIssue.setUpdatedAt(LocalDateTime.of(2026, 4, 16, 8, 0, 0));

        when(reviewSessionMapper.selectList(any())).thenReturn(List.of());
        when(qualityIssueMapper.selectList(any())).thenReturn(List.of(blockingIssue, resolvedIssue, warnIssue));
        when(securityIncidentMapper.selectList(any())).thenReturn(List.of());

        PageResult<ApprovalCenterItemVO> result = service.page(new ApprovalCenterQueryRequest());

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords()).extracting(ApprovalCenterItemVO::getId)
                .containsExactly("quality-blocking-1");
    }

    @Test
    void shouldBatchReviewWorkflowSessionsAndCollectFailures() {
        ReviewSessionMapper reviewSessionMapper = mock(ReviewSessionMapper.class);
        QualityIssueMapper qualityIssueMapper = mock(QualityIssueMapper.class);
        SecurityIncidentMapper securityIncidentMapper = mock(SecurityIncidentMapper.class);
        SecurityIncidentActionMapper securityIncidentActionMapper = mock(SecurityIncidentActionMapper.class);
        SpecMapper specMapper = mock(SpecMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        WorkflowNodeExecutionMapper workflowNodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        ReviewSessionService reviewSessionService = mock(ReviewSessionService.class);
        QualityIssueFeedbackService qualityIssueFeedbackService = mock(QualityIssueFeedbackService.class);
        SecurityIncidentService securityIncidentService = mock(SecurityIncidentService.class);

        ApprovalCenterServiceImpl service = new ApprovalCenterServiceImpl(
                reviewSessionMapper,
                qualityIssueMapper,
                securityIncidentMapper,
                securityIncidentActionMapper,
                specMapper,
                userMapper,
                workflowNodeExecutionMapper,
                reviewSessionService,
                qualityIssueFeedbackService,
                securityIncidentService
        );

        when(reviewSessionService.approve(eq("session-ok"), any())).thenReturn(null);
        when(reviewSessionService.approve(eq("session-fail"), any()))
                .thenThrow(new IllegalArgumentException("审批会话不存在"));

        ApprovalCenterBatchReviewRequest request = new ApprovalCenterBatchReviewRequest();
        request.setIds(List.of("session-ok", "session-fail"));
        request.setDecision("approve");
        request.setComment("统一通过");

        ApprovalCenterBatchActionResultVO result = service.batchReview(request);

        assertThat(result.getSuccessIds()).containsExactly("session-ok");
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailures()).singleElement().satisfies(failure -> {
            assertThat(failure.getId()).isEqualTo("session-fail");
            assertThat(failure.getReason()).isEqualTo("审批会话不存在");
        });
        verify(reviewSessionService).approve(eq("session-ok"), any());
        verify(reviewSessionService).approve(eq("session-fail"), any());
    }

    @Test
    void shouldDispatchBatchAssignByApprovalType() {
        ReviewSessionMapper reviewSessionMapper = mock(ReviewSessionMapper.class);
        QualityIssueMapper qualityIssueMapper = mock(QualityIssueMapper.class);
        SecurityIncidentMapper securityIncidentMapper = mock(SecurityIncidentMapper.class);
        SecurityIncidentActionMapper securityIncidentActionMapper = mock(SecurityIncidentActionMapper.class);
        SpecMapper specMapper = mock(SpecMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        WorkflowNodeExecutionMapper workflowNodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        ReviewSessionService reviewSessionService = mock(ReviewSessionService.class);
        QualityIssueFeedbackService qualityIssueFeedbackService = mock(QualityIssueFeedbackService.class);
        SecurityIncidentService securityIncidentService = mock(SecurityIncidentService.class);

        ApprovalCenterServiceImpl service = new ApprovalCenterServiceImpl(
                reviewSessionMapper,
                qualityIssueMapper,
                securityIncidentMapper,
                securityIncidentActionMapper,
                specMapper,
                userMapper,
                workflowNodeExecutionMapper,
                reviewSessionService,
                qualityIssueFeedbackService,
                securityIncidentService
        );

        SecurityUtil.setCurrentUserId("operator-1");

        ApprovalCenterBatchAssignRequest qualityRequest = new ApprovalCenterBatchAssignRequest();
        qualityRequest.setApprovalType("quality_issue");
        qualityRequest.setIds(List.of("quality-1", "quality-2"));
        qualityRequest.setAssigneeId("qa-2");

        ApprovalCenterBatchActionResultVO qualityResult = service.batchAssign(
                qualityRequest,
                new SecurityAuditContext("127.0.0.1", "JUnit")
        );

        assertThat(qualityResult.getSuccessIds()).containsExactly("quality-1", "quality-2");
        verify(qualityIssueFeedbackService).assignIssue("quality-1", "qa-2", "operator-1");
        verify(qualityIssueFeedbackService).assignIssue("quality-2", "qa-2", "operator-1");

        ApprovalCenterBatchAssignRequest incidentRequest = new ApprovalCenterBatchAssignRequest();
        incidentRequest.setApprovalType("security_incident");
        incidentRequest.setIds(List.of("incident-1"));
        incidentRequest.setAssigneeId("sec-2");
        incidentRequest.setAssigneeName("安全审批人");
        incidentRequest.setComment("转交二线安全团队处理");

        ApprovalCenterBatchActionResultVO incidentResult = service.batchAssign(
                incidentRequest,
                new SecurityAuditContext("127.0.0.1", "JUnit")
        );

        assertThat(incidentResult.getSuccessIds()).containsExactly("incident-1");
        verify(securityIncidentService).assign(eq("incident-1"), any(), any());
    }

    private ReviewSession buildPendingReviewSession(LocalDateTime createdAt) {
        ReviewSession session = new ReviewSession();
        session.setId("session-1");
        session.setTenantId("tenant-1");
        session.setSpecId("spec-1");
        session.setWorkflowInstanceId("wf-1");
        session.setWorkflowNodeId("review-1");
        session.setDocumentType("design");
        session.setOwner("owner-1");
        session.setDecisionStatus("pending");
        session.setStatus("pending");
        session.setReviewers(List.of(Map.of(
                "userId", "reviewer-1",
                "reviewerName", "架构师A",
                "role", "架构评审",
                "status", "pending"
        )));
        session.setCreatedAt(createdAt);
        session.setUpdatedAt(createdAt);
        return session;
    }

    private ReviewSession buildApprovedReviewSession(LocalDateTime approvedAt) {
        ReviewSession session = new ReviewSession();
        session.setId("session-2");
        session.setTenantId("tenant-1");
        session.setSpecId("spec-1");
        session.setWorkflowInstanceId("wf-1");
        session.setWorkflowNodeId("review-1");
        session.setDocumentType("design");
        session.setOwner("owner-1");
        session.setDecisionStatus("approved");
        session.setStatus("completed");
        session.setReviewActionUrl("/approval-center?type=workflow_review&id=session-2");
        session.setReviewers(List.of(Map.of(
                "userId", "reviewer-1",
                "role", "架构评审",
                "status", "approved",
                "submittedAt", approvedAt.toString()
        )));
        session.setCreatedAt(approvedAt.minusHours(1));
        session.setUpdatedAt(approvedAt);
        return session;
    }

    private QualityIssue buildResolvedQualityIssue(LocalDateTime updatedAt) {
        QualityIssue issue = new QualityIssue();
        issue.setId("quality-1");
        issue.setTenantId("tenant-1");
        issue.setSpecId("spec-1");
        issue.setWorkflowInstanceId("wf-1");
        issue.setWorkflowNodeId("quality-1");
        issue.setTitle("质量阻断");
        issue.setSummary("这个问题已经被处理");
        issue.setStatus("resolved");
        issue.setAssigneeId("qa-1");
        issue.setCreatedBy("owner-1");
        issue.setResolvedBy("qa-lead-1");
        issue.setResolvedAt(updatedAt);
        issue.setCreatedAt(updatedAt.minusHours(1));
        issue.setUpdatedAt(updatedAt);
        return issue;
    }

    private SecurityIncident buildPendingSecurityIncident(LocalDateTime createdAt) {
        SecurityIncident incident = new SecurityIncident();
        incident.setId("incident-1");
        incident.setTenantId("tenant-1");
        incident.setSourceId("source-1");
        incident.setSourceName("Agent 运行时");
        incident.setEventTitle("敏感信息外泄风险");
        incident.setEventDetail("需要人工确认后继续");
        incident.setStatus("assigned");
        incident.setAssignedTo("sec-1");
        incident.setCreatedBy("owner-1");
        incident.setCreatedAt(createdAt);
        incident.setUpdatedAt(createdAt);
        return incident;
    }

    private SecurityIncidentAction buildSecurityAction(LocalDateTime actionAt) {
        SecurityIncidentAction action = new SecurityIncidentAction();
        action.setIncidentId("incident-1");
        action.setOperatorId("sec-manager-1");
        action.setOperatorName("安全主管");
        action.setActionAt(actionAt);
        action.setCreatedAt(actionAt);
        return action;
    }

    private Spec buildSpec() {
        Spec spec = new Spec();
        spec.setId("spec-1");
        spec.setName("统一审批 Spec");
        return spec;
    }

    private WorkflowNodeExecution buildNodeExecution() {
        WorkflowNodeExecution execution = new WorkflowNodeExecution();
        execution.setId("node-exec-1");
        execution.setInstanceId("wf-1");
        execution.setNodeId("review-1");
        execution.setNodeLabel("方案审批");
        return execution;
    }

    private User buildUser(String id, String realName) {
        User user = new User();
        user.setId(id);
        user.setRealName(realName);
        user.setUsername(realName);
        return user;
    }
}
