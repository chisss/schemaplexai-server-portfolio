package com.schemaplexai.service.approval.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.model.dto.approval.ApprovalCenterBatchAssignRequest;
import com.schemaplexai.model.dto.approval.ApprovalCenterBatchReviewRequest;
import com.schemaplexai.dao.mapper.QualityIssueMapper;
import com.schemaplexai.dao.mapper.ReviewSessionMapper;
import com.schemaplexai.dao.mapper.SecurityIncidentActionMapper;
import com.schemaplexai.dao.mapper.SecurityIncidentMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.UserMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.model.dto.approval.ApprovalCenterQueryRequest;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.dto.security.SecurityIncidentActionRequest;
import com.schemaplexai.model.dto.workflow.ReviewDecisionRequest;
import com.schemaplexai.model.entity.QualityIssue;
import com.schemaplexai.model.entity.ReviewSession;
import com.schemaplexai.model.entity.SecurityIncident;
import com.schemaplexai.model.entity.SecurityIncidentAction;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.User;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.model.vo.approval.ApprovalCenterBatchActionFailureVO;
import com.schemaplexai.model.vo.approval.ApprovalCenterBatchActionResultVO;
import com.schemaplexai.model.vo.approval.ApprovalCenterItemVO;
import com.schemaplexai.service.approval.ApprovalCenterService;
import com.schemaplexai.service.quality.feedback.QualityIssueFeedbackService;
import com.schemaplexai.service.security.SecurityIncidentService;
import com.schemaplexai.service.workflow.ReviewSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 统一审批中心服务实现
 */
@Service
@RequiredArgsConstructor
public class ApprovalCenterServiceImpl implements ApprovalCenterService {

    private static final Set<String> QUALITY_PENDING_STATUSES = Set.of("open", "acknowledged");
    private static final Set<String> QUALITY_BLOCKING_DECISIONS = Set.of("fail", "pause");
    private static final Set<String> SECURITY_PENDING_STATUSES = Set.of("new", "assigned", "investigating", "escalated");
    private static final Set<String> REVIEW_DECISION_PENDING = Set.of("pending");
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int EXPORT_LIMIT = 5000;

    private final ReviewSessionMapper reviewSessionMapper;
    private final QualityIssueMapper qualityIssueMapper;
    private final SecurityIncidentMapper securityIncidentMapper;
    private final SecurityIncidentActionMapper securityIncidentActionMapper;
    private final SpecMapper specMapper;
    private final UserMapper userMapper;
    private final WorkflowNodeExecutionMapper workflowNodeExecutionMapper;
    private final ReviewSessionService reviewSessionService;
    private final QualityIssueFeedbackService qualityIssueFeedbackService;
    private final SecurityIncidentService securityIncidentService;

    @Override
    public PageResult<ApprovalCenterItemVO> page(ApprovalCenterQueryRequest request) {
        ApprovalCenterQueryRequest query = request == null ? new ApprovalCenterQueryRequest() : request;
        int page = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int size = query.getSize() == null || query.getSize() < 1 ? DEFAULT_PAGE_SIZE : Math.min(query.getSize(), MAX_PAGE_SIZE);
        int candidateLimit = StringUtils.hasText(query.getKeyword()) ? EXPORT_LIMIT : page * size;

        List<ApprovalCenterItemVO> filtered = listFilteredItems(query, candidateLimit);
        long total = StringUtils.hasText(query.getKeyword()) ? filtered.size() : countFilteredItems(query);
        int fromIndex = Math.min((page - 1) * size, filtered.size());
        int toIndex = Math.min(fromIndex + size, filtered.size());
        return new PageResult<>(filtered.subList(fromIndex, toIndex), total, page, size);
    }

    @Override
    public ApprovalCenterBatchActionResultVO batchReview(ApprovalCenterBatchReviewRequest request) {
        List<String> ids = request == null || request.getIds() == null ? List.of() : request.getIds().stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        List<String> successIds = new ArrayList<>();
        List<ApprovalCenterBatchActionFailureVO> failures = new ArrayList<>();
        String decision = defaultString(request != null ? request.getDecision() : null, "");
        for (String id : ids) {
            try {
                ReviewDecisionRequest decisionRequest = new ReviewDecisionRequest();
                decisionRequest.setComment(normalizeOptionalText(request.getComment()));
                decisionRequest.setModifyInstruction(normalizeOptionalText(request.getModifyInstruction()));
                switch (decision) {
                    case "approve" -> reviewSessionService.approve(id, decisionRequest);
                    case "reject" -> reviewSessionService.reject(id, decisionRequest);
                    case "request_modify" -> {
                        if (!StringUtils.hasText(decisionRequest.getComment())
                                && !StringUtils.hasText(decisionRequest.getModifyInstruction())) {
                            throw new IllegalArgumentException("退回重跑必须填写审批意见");
                        }
                        if (!StringUtils.hasText(decisionRequest.getModifyInstruction())) {
                            decisionRequest.setModifyInstruction(decisionRequest.getComment());
                        }
                        reviewSessionService.requestModify(id, decisionRequest);
                    }
                    default -> throw new IllegalArgumentException("不支持的审批动作: " + decision);
                }
                successIds.add(id);
            } catch (Exception ex) {
                failures.add(buildFailure(id, ex));
            }
        }
        return buildBatchResult(ids, successIds, failures);
    }

    @Override
    public ApprovalCenterBatchActionResultVO batchAssign(ApprovalCenterBatchAssignRequest request, SecurityAuditContext auditContext) {
        List<String> ids = request == null || request.getIds() == null ? List.of() : request.getIds().stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        List<String> successIds = new ArrayList<>();
        List<ApprovalCenterBatchActionFailureVO> failures = new ArrayList<>();
        String currentUserId = SecurityUtil.getCurrentUserId();
        for (String id : ids) {
            try {
                if ("quality_issue".equals(request.getApprovalType())) {
                    qualityIssueFeedbackService.assignIssue(id, request.getAssigneeId(), currentUserId);
                } else if ("security_incident".equals(request.getApprovalType())) {
                    if (!StringUtils.hasText(request.getComment())) {
                        throw new IllegalArgumentException("安全事件批量指派必须填写处置说明");
                    }
                    SecurityIncidentActionRequest actionRequest = new SecurityIncidentActionRequest();
                    actionRequest.setAssigneeId(request.getAssigneeId());
                    actionRequest.setAssigneeName(normalizeOptionalText(request.getAssigneeName()));
                    actionRequest.setComment(request.getComment().trim());
                    securityIncidentService.assign(id, actionRequest, auditContext);
                } else {
                    throw new IllegalArgumentException("当前类型不支持批量指派: " + request.getApprovalType());
                }
                successIds.add(id);
            } catch (Exception ex) {
                failures.add(buildFailure(id, ex));
            }
        }
        return buildBatchResult(ids, successIds, failures);
    }

    @Override
    public byte[] exportAudit(ApprovalCenterQueryRequest request) {
        List<ApprovalCenterItemVO> items = listFilteredItems(request == null ? new ApprovalCenterQueryRequest() : request, EXPORT_LIMIT);
        StringBuilder builder = new StringBuilder();
        builder.append('\uFEFF');
        builder.append("审批类型,标题,状态,是否待处理,申请人,当前审批人或处理人,最终审批人,关联Spec,关联节点,来源对象,创建时间,更新时间,截止时间,跳转地址\n");
        items.forEach(item -> builder.append(toCsvRow(item)));
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String resolveExportFileName() {
        return "approval-audit-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".csv";
    }

    private List<ApprovalCenterItemVO> listFilteredItems(ApprovalCenterQueryRequest request, int candidateLimit) {
        String tenantId = SecurityUtil.getCurrentTenantId();
        List<ApprovalCenterItemVO> items = new ArrayList<>();
        if (shouldLoadType(request, "workflow_review")) {
            items.addAll(loadWorkflowReviewItems(tenantId, request, candidateLimit));
        }
        if (shouldLoadType(request, "quality_issue")) {
            items.addAll(loadQualityIssueItems(tenantId, request, candidateLimit));
        }
        if (shouldLoadType(request, "security_incident")) {
            items.addAll(loadSecurityIncidentItems(tenantId, request, candidateLimit));
        }
        return items.stream()
                .filter(item -> matchesView(item, request.getView()))
                .filter(item -> matchesKeyword(item, request.getKeyword()))
                .sorted(resolveComparator(request.getView()))
                .toList();
    }

    private List<ApprovalCenterItemVO> loadWorkflowReviewItems(String tenantId, ApprovalCenterQueryRequest request, int limit) {
        List<ReviewSession> sessions = reviewSessionMapper.selectList(
                new LambdaQueryWrapper<ReviewSession>()
                        .select(ReviewSession::getId, ReviewSession::getTenantId, ReviewSession::getSpecId,
                                ReviewSession::getWorkflowInstanceId, ReviewSession::getWorkflowNodeId,
                                ReviewSession::getDocumentType, ReviewSession::getOwner, ReviewSession::getReviewers,
                                ReviewSession::getDeadline, ReviewSession::getDecisionStatus,
                                ReviewSession::getReviewActionUrl, ReviewSession::getCreatedAt, ReviewSession::getUpdatedAt)
                        .eq(StringUtils.hasText(tenantId), ReviewSession::getTenantId, tenantId)
                        .in(isPendingView(request), ReviewSession::getDecisionStatus, REVIEW_DECISION_PENDING)
                        .notIn(isProcessedView(request), ReviewSession::getDecisionStatus, REVIEW_DECISION_PENDING)
                        .orderByDesc(ReviewSession::getUpdatedAt)
                        .last(limitClause(limit))
        );
        if (sessions.isEmpty()) {
            return List.of();
        }

        Set<String> specIds = sessions.stream()
                .map(ReviewSession::getSpecId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        Map<String, Spec> specMap = specIds.isEmpty()
                ? Map.of()
                : specMapper.selectBatchIds(specIds).stream()
                .collect(Collectors.toMap(Spec::getId, item -> item, (left, right) -> left));

        Map<String, WorkflowNodeExecution> nodeExecutionMap = loadNodeExecutionMapByReviewSessionId(sessions.stream()
                .map(ReviewSession::getId)
                .filter(StringUtils::hasText)
                .toList());

        Map<String, User> userMap = loadUsers(resolveWorkflowReviewUserIds(sessions));

        return sessions.stream().map(session -> {
            ApprovalCenterItemVO item = new ApprovalCenterItemVO();
            Spec spec = specMap.get(session.getSpecId());
            WorkflowNodeExecution nodeExecution = nodeExecutionMap.get(session.getId());
            Map<String, Object> approverSnapshot = resolveApproverSnapshot(session, userMap);
            item.setId(session.getId());
            item.setApprovalType("workflow_review");
            item.setTitle(buildWorkflowReviewTitle(spec, nodeExecution, session));
            item.setSummary(buildWorkflowReviewSummary(session));
            item.setStatus(session.getDecisionStatus());
            item.setStatusLabel(resolveWorkflowReviewStatusLabel(session.getDecisionStatus()));
            item.setPending(REVIEW_DECISION_PENDING.contains(defaultString(session.getDecisionStatus(), "pending")));
            item.setSpecId(session.getSpecId());
            item.setSpecName(spec != null ? spec.getName() : null);
            item.setWorkflowInstanceId(session.getWorkflowInstanceId());
            item.setWorkflowNodeId(session.getWorkflowNodeId());
            item.setWorkflowNodeLabel(nodeExecution != null && StringUtils.hasText(nodeExecution.getNodeLabel())
                    ? nodeExecution.getNodeLabel() : session.getWorkflowNodeId());
            item.setDocumentType(session.getDocumentType());
            item.setApplicantId(session.getOwner());
            item.setApplicantName(resolveUserDisplayName(session.getOwner(), userMap));
            item.setAssigneeName(buildReviewerSummary(session.getReviewers(), userMap));
            item.setApproverId(stringValue(approverSnapshot.get("userId")));
            item.setApproverName(stringValue(approverSnapshot.get("reviewerName")));
            item.setApproverAt(resolveDateTime(approverSnapshot.get("submittedAt")));
            item.setDeadline(session.getDeadline());
            item.setActionUrl(StringUtils.hasText(session.getReviewActionUrl())
                    ? session.getReviewActionUrl()
                    : "/approval-center?type=workflow_review&id=" + session.getId());
            item.setCreatedAt(session.getCreatedAt());
            item.setUpdatedAt(session.getUpdatedAt());
            return item;
        }).toList();
    }

    private List<ApprovalCenterItemVO> loadQualityIssueItems(String tenantId, ApprovalCenterQueryRequest request, int limit) {
        List<QualityIssue> issues = qualityIssueMapper.selectList(
                new LambdaQueryWrapper<QualityIssue>()
                        .select(QualityIssue::getId, QualityIssue::getTenantId, QualityIssue::getCreatedBy,
                                QualityIssue::getCreatedAt, QualityIssue::getUpdatedAt, QualityIssue::getSpecId,
                                QualityIssue::getGateDecision, QualityIssue::getTitle, QualityIssue::getSummary,
                                QualityIssue::getWorkflowInstanceId, QualityIssue::getWorkflowNodeId,
                                QualityIssue::getStatus, QualityIssue::getAssigneeId,
                                QualityIssue::getResolvedBy, QualityIssue::getResolvedAt)
                        .eq(StringUtils.hasText(tenantId), QualityIssue::getTenantId, tenantId)
                        .in(QualityIssue::getGateDecision, QUALITY_BLOCKING_DECISIONS)
                        .in(isPendingView(request), QualityIssue::getStatus, QUALITY_PENDING_STATUSES)
                        .notIn(isProcessedView(request), QualityIssue::getStatus, QUALITY_PENDING_STATUSES)
                        .orderByDesc(QualityIssue::getUpdatedAt)
                        .last(limitClause(limit))
        );
        if (issues.isEmpty()) {
            return List.of();
        }
        Set<String> specIds = issues.stream()
                .map(QualityIssue::getSpecId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        Map<String, Spec> specMap = specIds.isEmpty()
                ? Map.of()
                : specMapper.selectBatchIds(specIds).stream()
                .collect(Collectors.toMap(Spec::getId, item -> item, (left, right) -> left));
        Map<String, User> userMap = loadUsers(issues.stream()
                .flatMap(item -> Stream.of(item.getAssigneeId(), item.getResolvedBy(), item.getCreatedBy()))
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet()));

        return issues.stream()
                .map(issue -> {
            ApprovalCenterItemVO item = new ApprovalCenterItemVO();
            Spec spec = StringUtils.hasText(issue.getSpecId()) ? specMap.get(issue.getSpecId()) : null;
            item.setId(issue.getId());
            item.setApprovalType("quality_issue");
            item.setTitle(issue.getTitle());
            item.setSummary(issue.getSummary());
            item.setStatus(issue.getStatus());
            item.setStatusLabel(resolveQualityStatusLabel(issue.getStatus()));
            item.setPending(QUALITY_PENDING_STATUSES.contains(defaultString(issue.getStatus(), "")));
            item.setSpecId(issue.getSpecId());
            item.setSpecName(spec != null ? spec.getName() : null);
            item.setWorkflowInstanceId(issue.getWorkflowInstanceId());
            item.setWorkflowNodeId(issue.getWorkflowNodeId());
            item.setWorkflowNodeLabel(issue.getWorkflowNodeId());
            item.setApplicantId(issue.getCreatedBy());
            item.setApplicantName(resolveUserDisplayName(issue.getCreatedBy(), userMap));
            item.setAssigneeId(issue.getAssigneeId());
            item.setAssigneeName(resolveUserDisplayName(issue.getAssigneeId(), userMap));
            item.setApproverId(issue.getResolvedBy());
            item.setApproverName(resolveUserDisplayName(issue.getResolvedBy(), userMap));
            item.setApproverAt(issue.getResolvedAt());
            item.setActionUrl("/approval-center?type=quality_issue&id=" + issue.getId());
            item.setCreatedAt(issue.getCreatedAt());
            item.setUpdatedAt(issue.getUpdatedAt());
            return item;
        }).toList();
    }

    private List<ApprovalCenterItemVO> loadSecurityIncidentItems(String tenantId, ApprovalCenterQueryRequest request, int limit) {
        List<SecurityIncident> incidents = securityIncidentMapper.selectList(
                new LambdaQueryWrapper<SecurityIncident>()
                        .select(SecurityIncident::getId, SecurityIncident::getTenantId, SecurityIncident::getCreatedBy,
                                SecurityIncident::getCreatedAt, SecurityIncident::getUpdatedAt,
                                SecurityIncident::getIncidentNo, SecurityIncident::getStatus,
                                SecurityIncident::getSourceId, SecurityIncident::getSourceName,
                                SecurityIncident::getEventTitle, SecurityIncident::getEventDetail,
                                SecurityIncident::getAssignedTo, SecurityIncident::getAssignedName,
                                SecurityIncident::getResolvedAt)
                        .eq(StringUtils.hasText(tenantId), SecurityIncident::getTenantId, tenantId)
                        .in(isPendingView(request), SecurityIncident::getStatus, SECURITY_PENDING_STATUSES)
                        .notIn(isProcessedView(request), SecurityIncident::getStatus, SECURITY_PENDING_STATUSES)
                        .orderByDesc(SecurityIncident::getUpdatedAt)
                        .last(limitClause(limit))
        );
        if (incidents.isEmpty()) {
            return List.of();
        }

        Map<String, SecurityIncidentAction> latestActionMap = loadLatestSecurityActionMap(incidents.stream()
                .map(SecurityIncident::getId)
                .filter(StringUtils::hasText)
                .toList());
        Map<String, User> userMap = loadUsers(incidents.stream()
                .flatMap(item -> Stream.of(item.getAssignedTo(), item.getCreatedBy()))
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet()));

        return incidents.stream().map(incident -> {
            ApprovalCenterItemVO item = new ApprovalCenterItemVO();
            SecurityIncidentAction latestAction = latestActionMap.get(incident.getId());
            item.setId(incident.getId());
            item.setApprovalType("security_incident");
            item.setTitle(StringUtils.hasText(incident.getEventTitle()) ? incident.getEventTitle() : incident.getIncidentNo());
            item.setSummary(incident.getEventDetail());
            item.setStatus(incident.getStatus());
            item.setStatusLabel(resolveSecurityStatusLabel(incident.getStatus()));
            item.setPending(SECURITY_PENDING_STATUSES.contains(defaultString(incident.getStatus(), "")));
            item.setSourceId(incident.getSourceId());
            item.setSourceName(incident.getSourceName());
            item.setApplicantId(incident.getCreatedBy());
            item.setApplicantName(resolveUserDisplayName(incident.getCreatedBy(), userMap));
            item.setAssigneeId(incident.getAssignedTo());
            item.setAssigneeName(StringUtils.hasText(incident.getAssignedName())
                    ? incident.getAssignedName()
                    : resolveUserDisplayName(incident.getAssignedTo(), userMap));
            item.setApproverId(latestAction != null ? latestAction.getOperatorId() : null);
            item.setApproverName(latestAction != null ? latestAction.getOperatorName() : null);
            item.setApproverAt(latestAction != null ? latestAction.getActionAt() : incident.getResolvedAt());
            item.setActionUrl("/approval-center?type=security_incident&id=" + incident.getId());
            item.setCreatedAt(incident.getCreatedAt());
            item.setUpdatedAt(incident.getUpdatedAt());
            return item;
        }).toList();
    }

    private Map<String, WorkflowNodeExecution> loadNodeExecutionMapByReviewSessionId(List<String> reviewSessionIds) {
        if (reviewSessionIds.isEmpty()) {
            return Map.of();
        }
        return workflowNodeExecutionMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeExecution>()
                        .select(WorkflowNodeExecution::getId, WorkflowNodeExecution::getReviewSessionId,
                                WorkflowNodeExecution::getNodeLabel, WorkflowNodeExecution::getActionUrl,
                                WorkflowNodeExecution::getCreatedAt)
                        .in(WorkflowNodeExecution::getReviewSessionId, reviewSessionIds)
                        .orderByDesc(WorkflowNodeExecution::getCreatedAt)
        ).stream()
                .filter(item -> StringUtils.hasText(item.getReviewSessionId()))
                .collect(Collectors.toMap(WorkflowNodeExecution::getReviewSessionId, item -> item,
                        (left, right) -> left, LinkedHashMap::new));
    }

    @SuppressWarnings("unchecked")
    private Set<String> resolveWorkflowReviewUserIds(List<ReviewSession> sessions) {
        return sessions.stream().flatMap(session -> {
            List<Object> reviewers = session.getReviewers();
            List<String> ids = new ArrayList<>();
            if (StringUtils.hasText(session.getOwner())) {
                ids.add(session.getOwner());
            }
            if (reviewers != null) {
                reviewers.stream()
                        .filter(Map.class::isInstance)
                        .map(item -> (Map<String, Object>) item)
                        .map(item -> stringValue(item.get("userId")))
                        .filter(StringUtils::hasText)
                        .forEach(ids::add);
            }
            return ids.stream();
        }).collect(Collectors.toSet());
    }

    private Map<String, User> loadUsers(Set<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, item -> item, (left, right) -> left));
    }

    private Map<String, SecurityIncidentAction> loadLatestSecurityActionMap(List<String> incidentIds) {
        if (incidentIds.isEmpty()) {
            return Map.of();
        }
        return securityIncidentActionMapper.selectList(
                new LambdaQueryWrapper<SecurityIncidentAction>()
                        .in(SecurityIncidentAction::getIncidentId, incidentIds)
                        .orderByDesc(SecurityIncidentAction::getActionAt)
                        .orderByDesc(SecurityIncidentAction::getCreatedAt)
        ).stream().collect(Collectors.toMap(SecurityIncidentAction::getIncidentId, item -> item, (left, right) -> left, LinkedHashMap::new));
    }

    private long countFilteredItems(ApprovalCenterQueryRequest request) {
        String tenantId = SecurityUtil.getCurrentTenantId();
        long total = 0;
        if (shouldLoadType(request, "workflow_review")) {
            total += reviewSessionMapper.selectCount(new LambdaQueryWrapper<ReviewSession>()
                    .eq(StringUtils.hasText(tenantId), ReviewSession::getTenantId, tenantId)
                    .in(isPendingView(request), ReviewSession::getDecisionStatus, REVIEW_DECISION_PENDING)
                    .notIn(isProcessedView(request), ReviewSession::getDecisionStatus, REVIEW_DECISION_PENDING));
        }
        if (shouldLoadType(request, "quality_issue")) {
            total += qualityIssueMapper.selectCount(new LambdaQueryWrapper<QualityIssue>()
                    .eq(StringUtils.hasText(tenantId), QualityIssue::getTenantId, tenantId)
                    .in(QualityIssue::getGateDecision, QUALITY_BLOCKING_DECISIONS)
                    .in(isPendingView(request), QualityIssue::getStatus, QUALITY_PENDING_STATUSES)
                    .notIn(isProcessedView(request), QualityIssue::getStatus, QUALITY_PENDING_STATUSES));
        }
        if (shouldLoadType(request, "security_incident")) {
            total += securityIncidentMapper.selectCount(new LambdaQueryWrapper<SecurityIncident>()
                    .eq(StringUtils.hasText(tenantId), SecurityIncident::getTenantId, tenantId)
                    .in(isPendingView(request), SecurityIncident::getStatus, SECURITY_PENDING_STATUSES)
                    .notIn(isProcessedView(request), SecurityIncident::getStatus, SECURITY_PENDING_STATUSES));
        }
        return total;
    }

    private boolean shouldLoadType(ApprovalCenterQueryRequest request, String type) {
        return !StringUtils.hasText(request.getType()) || type.equalsIgnoreCase(request.getType());
    }

    private boolean isPendingView(ApprovalCenterQueryRequest request) {
        return "pending".equalsIgnoreCase(defaultString(request.getView(), "pending"));
    }

    private boolean isProcessedView(ApprovalCenterQueryRequest request) {
        return "processed".equalsIgnoreCase(defaultString(request.getView(), "pending"));
    }

    private String limitClause(int limit) {
        return "LIMIT " + Math.max(1, Math.min(limit, EXPORT_LIMIT));
    }

    private boolean matchesView(ApprovalCenterItemVO item, String view) {
        String resolvedView = StringUtils.hasText(view) ? view : "pending";
        boolean pending = Boolean.TRUE.equals(item.getPending());
        if ("all".equalsIgnoreCase(resolvedView)) {
            return true;
        }
        if ("processed".equalsIgnoreCase(resolvedView)) {
            return !pending;
        }
        return pending;
    }

    private boolean matchesType(ApprovalCenterItemVO item, String type) {
        return !StringUtils.hasText(type) || type.equalsIgnoreCase(item.getApprovalType());
    }

    private boolean matchesKeyword(ApprovalCenterItemVO item, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        String text = keyword.trim().toLowerCase();
        return contains(item.getTitle(), text)
                || contains(item.getSummary(), text)
                || contains(item.getSpecName(), text)
                || contains(item.getWorkflowNodeLabel(), text)
                || contains(item.getApplicantName(), text)
                || contains(item.getAssigneeName(), text)
                || contains(item.getApproverName(), text)
                || contains(item.getSourceName(), text);
    }

    private Comparator<ApprovalCenterItemVO> resolveComparator(String view) {
        if ("pending".equalsIgnoreCase(view)) {
            return Comparator.comparing(ApprovalCenterItemVO::getCreatedAt,
                    Comparator.nullsLast(LocalDateTime::compareTo)).reversed();
        }
        return Comparator.comparing(ApprovalCenterItemVO::getUpdatedAt,
                Comparator.nullsLast(LocalDateTime::compareTo)).reversed();
    }

    private String buildWorkflowReviewTitle(Spec spec, WorkflowNodeExecution nodeExecution, ReviewSession session) {
        String nodeLabel = nodeExecution != null && StringUtils.hasText(nodeExecution.getNodeLabel())
                ? nodeExecution.getNodeLabel() : session.getWorkflowNodeId();
        if (spec != null && StringUtils.hasText(spec.getName())) {
            return spec.getName() + " / " + defaultString(nodeLabel, "人工审批");
        }
        return defaultString(nodeLabel, "人工审批");
    }

    @SuppressWarnings("unchecked")
    private String buildWorkflowReviewSummary(ReviewSession session) {
        if (session == null || session.getReviewers() == null || session.getReviewers().isEmpty()) {
            return null;
        }
        long reviewerCount = session.getReviewers().stream()
                .filter(Map.class::isInstance)
                .count();
        return "共 " + reviewerCount + " 位审批人";
    }

    @SuppressWarnings("unchecked")
    private String buildReviewerSummary(List<Object> reviewers, Map<String, User> userMap) {
        if (reviewers == null || reviewers.isEmpty()) {
            return null;
        }
        return reviewers.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .map(item -> {
                    String snapshotName = stringValue(item.get("reviewerName"));
                    if (StringUtils.hasText(snapshotName)) {
                        return snapshotName;
                    }
                    return resolveUserDisplayName(stringValue(item.get("userId")), userMap);
                })
                .filter(StringUtils::hasText)
                .distinct()
                .limit(3)
                .collect(Collectors.joining("、"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveApproverSnapshot(ReviewSession session, Map<String, User> userMap) {
        if (session == null || session.getReviewers() == null || session.getReviewers().isEmpty()) {
            return Map.of();
        }
        return session.getReviewers().stream()
                .filter(Map.class::isInstance)
                .map(item -> {
                    Map<String, Object> reviewer = new HashMap<>((Map<String, Object>) item);
                    if (!StringUtils.hasText(stringValue(reviewer.get("reviewerName")))) {
                        reviewer.put("reviewerName", resolveUserDisplayName(stringValue(reviewer.get("userId")), userMap));
                    }
                    return reviewer;
                })
                .filter(item -> Set.of("approved", "rejected", "request_modify").contains(stringValue(item.get("status"))))
                .max(Comparator.comparing(item -> resolveDateTime(item.get("submittedAt")),
                        Comparator.nullsLast(LocalDateTime::compareTo)))
                .orElse(Map.of());
    }

    private String resolveWorkflowReviewStatusLabel(String status) {
        return switch (defaultString(status, "pending")) {
            case "approved" -> "已通过";
            case "rejected" -> "已驳回";
            case "request_modify" -> "已退回";
            default -> "待审批";
        };
    }

    private String resolveQualityStatusLabel(String status) {
        return switch (defaultString(status, "")) {
            case "open" -> "待处理";
            case "acknowledged" -> "已确认";
            case "resolving" -> "处理中";
            case "resolved" -> "已解决";
            case "ignored" -> "已忽略";
            default -> status;
        };
    }

    private String resolveSecurityStatusLabel(String status) {
        return switch (defaultString(status, "")) {
            case "new" -> "待处理";
            case "assigned" -> "已分派";
            case "investigating" -> "处理中";
            case "resolved" -> "已解决";
            case "ignored" -> "已忽略";
            case "escalated" -> "已升级";
            default -> status;
        };
    }

    private String resolveUserDisplayName(String userId, Map<String, User> userMap) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        User user = userMap.get(userId);
        if (user == null) {
            return userId;
        }
        if (StringUtils.hasText(user.getRealName())) {
            return user.getRealName();
        }
        if (StringUtils.hasText(user.getUsername())) {
            return user.getUsername();
        }
        return userId;
    }

    private ApprovalCenterBatchActionResultVO buildBatchResult(List<String> ids,
                                                               List<String> successIds,
                                                               List<ApprovalCenterBatchActionFailureVO> failures) {
        ApprovalCenterBatchActionResultVO result = new ApprovalCenterBatchActionResultVO();
        result.setTotalCount(ids.size());
        result.setSuccessCount(successIds.size());
        result.setFailedCount(failures.size());
        result.setSuccessIds(successIds);
        result.setFailures(failures);
        return result;
    }

    private ApprovalCenterBatchActionFailureVO buildFailure(String id, Exception ex) {
        ApprovalCenterBatchActionFailureVO failure = new ApprovalCenterBatchActionFailureVO();
        failure.setId(id);
        failure.setReason(StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName());
        return failure;
    }

    private String toCsvRow(ApprovalCenterItemVO item) {
        return Stream.of(
                        resolveApprovalTypeLabel(item.getApprovalType()),
                        defaultString(item.getTitle(), "-"),
                        defaultString(item.getStatusLabel(), defaultString(item.getStatus(), "-")),
                        Boolean.TRUE.equals(item.getPending()) ? "是" : "否",
                        defaultString(item.getApplicantName(), "-"),
                        defaultString(item.getAssigneeName(), item.getApproverName()),
                        defaultString(item.getApproverName(), "-"),
                        defaultString(item.getSpecName(), item.getSpecId()),
                        defaultString(item.getWorkflowNodeLabel(), item.getWorkflowNodeId()),
                        defaultString(item.getSourceName(), item.getSourceId()),
                        formatCsvDateTime(item.getCreatedAt()),
                        formatCsvDateTime(item.getUpdatedAt()),
                        formatCsvDateTime(item.getDeadline()),
                        defaultString(item.getActionUrl(), "-")
                )
                .map(this::escapeCsv)
                .collect(Collectors.joining(",")) + "\n";
    }

    private String resolveApprovalTypeLabel(String approvalType) {
        return switch (defaultString(approvalType, "")) {
            case "workflow_review" -> "工作流审批";
            case "quality_issue" -> "质量阻断";
            case "security_incident" -> "安全事件";
            default -> approvalType;
        };
    }

    private String formatCsvDateTime(LocalDateTime value) {
        return value == null ? "-" : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(value);
    }

    private String escapeCsv(String value) {
        String raw = value == null ? "" : value;
        String escaped = raw.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private String normalizeOptionalText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private boolean contains(String source, String keyword) {
        return StringUtils.hasText(source) && source.toLowerCase().contains(keyword);
    }

    private String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private LocalDateTime resolveDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        try {
            return LocalDateTime.parse(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }
}
