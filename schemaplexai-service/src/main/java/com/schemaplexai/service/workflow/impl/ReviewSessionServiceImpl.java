package com.schemaplexai.service.workflow.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.ReviewNotificationConstant;
import com.schemaplexai.common.enums.ReviewDecisionStatusEnum;
import com.schemaplexai.common.enums.ReviewCommentLevelEnum;
import com.schemaplexai.common.enums.ReviewSessionStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.ReviewCommentMapper;
import com.schemaplexai.dao.mapper.ReviewSessionMapper;
import com.schemaplexai.dao.mapper.RoleMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.UserMapper;
import com.schemaplexai.dao.mapper.UserRoleMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.model.converter.ReviewCommentConverter;
import com.schemaplexai.model.converter.ReviewSessionConverter;
import com.schemaplexai.model.dto.workflow.ReviewCommentCreateRequest;
import com.schemaplexai.model.dto.workflow.ReviewDecisionRequest;
import com.schemaplexai.model.dto.workflow.ReviewSessionCreateRequest;
import com.schemaplexai.model.entity.ReviewComment;
import com.schemaplexai.model.entity.ReviewSession;
import com.schemaplexai.model.entity.Role;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.User;
import com.schemaplexai.model.entity.UserRole;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.model.vo.workflow.ReviewCommentVO;
import com.schemaplexai.model.vo.workflow.ReviewSessionVO;
import com.schemaplexai.model.vo.workflow.ReviewSummaryVO;
import com.schemaplexai.service.notification.InAppMessageService;
import com.schemaplexai.service.workflow.WorkflowInstanceService;
import com.schemaplexai.service.workflow.ReviewSessionService;
import com.schemaplexai.service.workflow.runtime.ReviewNotificationHelper;
import com.schemaplexai.service.workflow.validator.ReviewValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 评审会话服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewSessionServiceImpl implements ReviewSessionService {

    private final ReviewSessionMapper sessionMapper;
    private final ReviewCommentMapper commentMapper;
    private final ReviewSessionConverter sessionConverter;
    private final ReviewCommentConverter commentConverter;
    private final ReviewValidator reviewValidator;
    private final WorkflowInstanceService workflowInstanceService;
    private final InAppMessageService inAppMessageService;
    private final SpecMapper specMapper;
    private final UserMapper userMapper;
    private final WorkflowNodeExecutionMapper workflowNodeExecutionMapper;
    private final ReviewNotificationHelper reviewNotificationHelper;
    private final RabbitTemplate rabbitTemplate;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewSessionVO create(ReviewSessionCreateRequest request) {
        var session = new ReviewSession();
        session.setTenantId(request.getTenantId() != null ? request.getTenantId() : SecurityUtil.getCurrentTenantId());
        session.setSpecId(request.getSpecId());
        session.setWorkflowInstanceId(request.getWorkflowInstanceId());
        session.setWorkflowNodeId(request.getWorkflowNodeId());
        session.setDocumentType(request.getDocumentType());
        session.setOwner(request.getOwner() != null ? request.getOwner() : SecurityUtil.getCurrentUserId());

        // 创建会话时固化审批人姓名快照，避免历史审批只能依赖实时用户表回查
        Set<String> reviewerIds = request.getReviewers().stream()
                .map(item -> item.get("userId"))
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        Map<String, User> reviewerUserMap = reviewerIds.isEmpty()
                ? Map.of()
                : userMapper.selectBatchIds(reviewerIds).stream()
                .collect(Collectors.toMap(User::getId, item -> item, (left, right) -> left));

        // 构建评审人列表，每人初始状态为 pending
        List<Object> reviewers = request.getReviewers().stream()
                .map(r -> {
                    Map<String, Object> reviewer = new HashMap<>();
                    String reviewerId = r.get("userId");
                    reviewer.put("userId", reviewerId);
                    String reviewerName = StringUtils.hasText(r.get("reviewerName"))
                            ? r.get("reviewerName")
                            : resolveUserDisplayName(reviewerId, reviewerUserMap);
                    if (StringUtils.hasText(reviewerName)) {
                        reviewer.put("reviewerName", reviewerName);
                    }
                    reviewer.put("role", r.get("role"));
                    reviewer.put("status", ReviewSessionStatusEnum.PENDING.getCode());
                    reviewer.put("submittedAt", null);
                    return (Object) reviewer;
                })
                .toList();
        session.setReviewers(reviewers);

        // 计算截止时间
        int hours = request.getDeadlineHours() != null ? request.getDeadlineHours() : 48;
        session.setDeadline(LocalDateTime.now().plusHours(hours));
        session.setTimeoutStrategy(request.getTimeoutStrategy());
        session.setStatus(ReviewSessionStatusEnum.PENDING.getCode());
        session.setDecisionStatus(ReviewDecisionStatusEnum.PENDING.getCode());
        session.setReviewActionUrl(normalizeReviewActionUrl(request.getReviewActionUrl()));
        session.setMessageTemplateId(request.getMessageTemplateId());

        sessionMapper.insert(session);

        log.info("创建评审会话: sessionId={}, specId={}, documentType={}, reviewerCount={}",
                session.getId(), request.getSpecId(), request.getDocumentType(), reviewers.size());
        return enrichWithSummary(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewCommentVO submitComment(String sessionId, ReviewCommentCreateRequest request) {
        var session = requireExists(sessionId);

        // 校验当前用户是评审人且未重复提交
        reviewValidator.validateIsReviewer(session);
        reviewValidator.validateNotDuplicate(sessionId);

        // 创建评审意见
        var comment = new ReviewComment();
        comment.setSessionId(sessionId);
        comment.setReviewerId(SecurityUtil.getCurrentUserId());
        comment.setLevel(request.getLevel());
        comment.setCategory(request.getCategory());
        comment.setContent(request.getContent());
        comment.setLocation(request.getLocation());
        commentMapper.insert(comment);

        // 更新评审人状态为 submitted
        updateReviewerStatus(session, SecurityUtil.getCurrentUserId(), "submitted");

        // 会话状态从 pending → in_progress
        if (ReviewSessionStatusEnum.PENDING.getCode().equals(session.getStatus())) {
            session.setStatus(ReviewSessionStatusEnum.IN_PROGRESS.getCode());
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
        }

        // 检查是否所有评审人都已提交
        checkAndCompleteSession(sessionId);

        log.info("提交评审意见: sessionId={}, reviewerId={}, level={}",
                sessionId, SecurityUtil.getCurrentUserId(), request.getLevel());
        return commentConverter.toVO(comment);
    }

    @Override
    public ReviewSessionVO getWithSummary(String sessionId) {
        var session = requireExists(sessionId);
        return enrichWithSummary(session);
    }

    @Override
    public ReviewSessionVO getLatestByWorkflowNode(String workflowInstanceId, String workflowNodeId) {
        if (workflowInstanceId == null || workflowNodeId == null) {
            return null;
        }
        ReviewSession session = sessionMapper.selectOne(
                new LambdaQueryWrapper<ReviewSession>()
                        .eq(ReviewSession::getWorkflowInstanceId, workflowInstanceId)
                        .eq(ReviewSession::getWorkflowNodeId, workflowNodeId)
                        .orderByDesc(ReviewSession::getCreatedAt)
                        .last("LIMIT 1")
        );
        return session == null ? null : enrichWithSummary(session);
    }

    @Override
    public List<ReviewSessionVO> getMyPending() {
        var currentUserId = SecurityUtil.getCurrentUserId();

        // 查询状态为 pending 或 in_progress 的会话
        var sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<ReviewSession>()
                        .in(ReviewSession::getStatus, ReviewSessionStatusEnum.PENDING.getCode(), ReviewSessionStatusEnum.IN_PROGRESS.getCode())
                        .orderByDesc(ReviewSession::getCreatedAt)
        );

        // 过滤当前用户是评审人的会话
        return sessions.stream()
                .filter(s -> isReviewerInSession(s, currentUserId))
                .map(this::enrichWithSummary)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateActionUrl(String sessionId, String actionUrl) {
        ReviewSession session = requireExists(sessionId);
        session.setReviewActionUrl(normalizeReviewActionUrl(actionUrl));
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewSessionVO approve(String sessionId, ReviewDecisionRequest request) {
        ReviewSession session = requirePendingSession(sessionId);
        reviewValidator.validateIsReviewer(session);
        updateReviewerStatus(session, SecurityUtil.getCurrentUserId(), "approved");
        session.setDecisionStatus(ReviewDecisionStatusEnum.APPROVED.getCode());
        session.setStatus(ReviewSessionStatusEnum.COMPLETED.getCode());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
        archivePendingReviewMessages(session);
        workflowInstanceService.approveNode(session.getWorkflowInstanceId(), session.getWorkflowNodeId(),
                request != null ? request.getComment() : null);
        return enrichWithSummary(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewSessionVO reject(String sessionId, ReviewDecisionRequest request) {
        ReviewSession session = requirePendingSession(sessionId);
        reviewValidator.validateIsReviewer(session);
        updateReviewerStatus(session, SecurityUtil.getCurrentUserId(), "rejected");
        session.setDecisionStatus(ReviewDecisionStatusEnum.REJECTED.getCode());
        session.setStatus(ReviewSessionStatusEnum.COMPLETED.getCode());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
        archivePendingReviewMessages(session);
        workflowInstanceService.rejectNode(session.getWorkflowInstanceId(), session.getWorkflowNodeId(),
                request != null ? request.getComment() : null,
                request != null ? request.getRollbackToNodeId() : null);
        return enrichWithSummary(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewSessionVO requestModify(String sessionId, ReviewDecisionRequest request) {
        ReviewSession session = requirePendingSession(sessionId);
        reviewValidator.validateIsReviewer(session);
        updateReviewerStatus(session, SecurityUtil.getCurrentUserId(), "request_modify");
        session.setDecisionStatus(ReviewDecisionStatusEnum.REQUEST_MODIFY.getCode());
        session.setStatus(ReviewSessionStatusEnum.COMPLETED.getCode());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
        String modifyInstruction = null;
        if (request != null) {
            modifyInstruction = StringUtils.hasText(request.getModifyInstruction())
                    ? request.getModifyInstruction() : request.getComment();
        }
        archivePendingReviewMessages(session);
        workflowInstanceService.requestModify(session.getWorkflowInstanceId(), session.getWorkflowNodeId(),
                modifyInstruction);
        return enrichWithSummary(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void checkAndCompleteSession(String sessionId) {
        var session = sessionMapper.selectById(sessionId);
        if (session == null || ReviewSessionStatusEnum.COMPLETED.getCode().equals(session.getStatus()) || ReviewSessionStatusEnum.TIMEOUT.getCode().equals(session.getStatus())) {
            return;
        }

        // 检查所有评审人是否都已提交
        boolean allSubmitted = isAllReviewersSubmitted(session);
        if (allSubmitted) {
            session.setStatus(ReviewSessionStatusEnum.COMPLETED.getCode());
            sessionMapper.updateById(session);
            log.info("评审会话已完成: sessionId={}", sessionId);

            String decision = aggregateDecision(session);

            // 发送评审完成事件到 MQ（供工作流引擎消费）
            rabbitTemplate.convertAndSend(ReviewNotificationConstant.MQ_EXCHANGE, Map.of(
                    "eventType", ReviewNotificationConstant.EVENT_TYPE_REVIEW_COMPLETED,
                    "sessionId", sessionId,
                    "specId", session.getSpecId(),
                    "workflowInstanceId", session.getWorkflowInstanceId(),
                    "workflowNodeId", session.getWorkflowNodeId(),
                    "decision", decision));

            // 发送站内信通知给会话创建者
            reviewNotificationHelper.sendReviewNotification(
                    session.getTenantId(),
                    ReviewNotificationConstant.BUSINESS_TYPE_COMPLETED,
                    ReviewNotificationConstant.DEFAULT_COMPLETED_TITLE,
                    ReviewNotificationConstant.DEFAULT_COMPLETED_CONTENT,
                    Map.of("sessionTitle", session.getId() != null ? session.getId() : sessionId,
                            "decision", decision),
                    sessionId,
                    List.of(session.getOwner()));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleTimeoutSessions() {
        // 查询已超时但未完成的会话
        var timeoutSessions = sessionMapper.selectList(
                new LambdaQueryWrapper<ReviewSession>()
                        .in(ReviewSession::getStatus, ReviewSessionStatusEnum.PENDING.getCode(), ReviewSessionStatusEnum.IN_PROGRESS.getCode())
                        .le(ReviewSession::getDeadline, LocalDateTime.now())
        );

        for (var session : timeoutSessions) {
            String strategy = session.getTimeoutStrategy();
            switch (strategy != null ? strategy : "escalate") {
                case "auto_pass":
                    // 自动通过：将会话标记为完成
                    session.setStatus(ReviewSessionStatusEnum.COMPLETED.getCode());
                    sessionMapper.updateById(session);
                    log.info("评审会话超时自动通过: sessionId={}", session.getId());
                    break;

                case "remind":
                    // 提醒未提交的评审人
                    List<String> pendingReviewerIds = findPendingReviewerIds(session);
                    reviewNotificationHelper.sendReviewNotification(
                            session.getTenantId(),
                            ReviewNotificationConstant.BUSINESS_TYPE_REMIND,
                            ReviewNotificationConstant.DEFAULT_REMIND_TITLE,
                            ReviewNotificationConstant.DEFAULT_REMIND_CONTENT,
                            Map.of("sessionTitle", session.getId() != null ? session.getId() : session.getId(),
                                    "deadline", session.getDeadline() != null ? session.getDeadline().toString() : ""),
                            session.getId(),
                            pendingReviewerIds);
                    log.info("评审会话超时提醒: sessionId={}, pendingReviewers={}", session.getId(), pendingReviewerIds.size());
                    break;

                case "escalate":
                default:
                    // 升级处理：标记为超时状态
                    session.setStatus(ReviewSessionStatusEnum.TIMEOUT.getCode());
                    sessionMapper.updateById(session);
                    log.info("评审会话超时升级: sessionId={}", session.getId());

                    // 通知会话 owner 和管理员
                    List<String> escalateRecipients = resolveEscalateRecipients(session);
                    reviewNotificationHelper.sendReviewNotification(
                            session.getTenantId(),
                            ReviewNotificationConstant.BUSINESS_TYPE_ESCALATE,
                            ReviewNotificationConstant.DEFAULT_ESCALATE_TITLE,
                            ReviewNotificationConstant.DEFAULT_ESCALATE_CONTENT,
                            Map.of("sessionTitle", session.getId() != null ? session.getId() : session.getId()),
                            session.getId(),
                            escalateRecipients);
                    break;
            }
        }
    }

    // ===== 私有方法 =====

    private ReviewSession requireExists(String id) {
        var session = sessionMapper.selectById(id);
        if (session == null) {
            throw new BusinessException(ResultCode.REVIEW_SESSION_NOT_FOUND);
        }
        return session;
    }

    private ReviewSession requirePendingSession(String id) {
        ReviewSession session = requireExists(id);
        if (!ReviewDecisionStatusEnum.PENDING.getCode().equals(session.getDecisionStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前评审任务已处理");
        }
        if (!ReviewSessionStatusEnum.PENDING.getCode().equals(session.getStatus())
                && !ReviewSessionStatusEnum.IN_PROGRESS.getCode().equals(session.getStatus())) {
            throw new BusinessException(ResultCode.WORKFLOW_STATUS_NOT_ALLOWED);
        }
        return session;
    }

    /**
     * 丰富会话 VO，包含评审意见和汇总信息
     */
    private ReviewSessionVO enrichWithSummary(ReviewSession session) {
        var vo = sessionConverter.toVO(session);
        vo.setReviewActionUrl(normalizeReviewActionUrl(vo.getReviewActionUrl()));
        vo.setReviewers(enrichReviewerSnapshots(session.getReviewers()));
        enrichMetadata(session, vo);
        enrichApproverSnapshot(vo);

        // 查询评审意见
        var comments = commentMapper.selectList(
                new LambdaQueryWrapper<ReviewComment>()
                        .eq(ReviewComment::getSessionId, session.getId())
                        .orderByAsc(ReviewComment::getCreatedAt)
        );
        vo.setComments(commentConverter.toVOList(comments));

        // 构建汇总
        var summary = buildSummary(session, comments);
        vo.setSummary(summary);

        return vo;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> enrichReviewerSnapshots(List<Object> reviewers) {
        if (reviewers == null || reviewers.isEmpty()) {
            return List.of();
        }
        Set<String> userIds = reviewers.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .map(item -> stringValue(item.get("userId")))
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        Map<String, User> userMap = userIds.isEmpty()
                ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, item -> item, (left, right) -> left));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : reviewers) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> reviewer = new HashMap<>((Map<String, Object>) rawMap);
            if (!StringUtils.hasText(stringValue(reviewer.get("reviewerName")))) {
                reviewer.put("reviewerName", resolveUserDisplayName(stringValue(reviewer.get("userId")), userMap));
            }
            result.add(reviewer);
        }
        return result;
    }

    private String normalizeReviewActionUrl(String reviewActionUrl) {
        if (!StringUtils.hasText(reviewActionUrl)) {
            return reviewActionUrl;
        }
        try {
            URI uri = URI.create(reviewActionUrl);
            if (uri.isAbsolute()) {
                StringBuilder normalized = new StringBuilder(StringUtils.hasText(uri.getPath()) ? uri.getPath() : "/");
                if (StringUtils.hasText(uri.getQuery())) {
                    normalized.append("?").append(uri.getQuery());
                }
                if (StringUtils.hasText(uri.getFragment())) {
                    normalized.append("#").append(uri.getFragment());
                }
                return normalized.toString();
            }
        } catch (IllegalArgumentException ex) {
            log.warn("评审入口地址格式非法，按原值返回: reviewActionUrl={}", reviewActionUrl);
            return reviewActionUrl;
        }
        return reviewActionUrl.startsWith("/") ? reviewActionUrl : "/" + reviewActionUrl;
    }

    /**
     * 构建评审汇总
     */
    @SuppressWarnings("unchecked")
    private ReviewSummaryVO buildSummary(ReviewSession session, List<ReviewComment> comments) {
        var summary = new ReviewSummaryVO();
        summary.setTotalComments(comments.size());

        long criticalCount = comments.stream()
                .filter(c -> ReviewCommentLevelEnum.CRITICAL.getCode().equals(c.getLevel()))
                .count();
        long warningCount = comments.stream()
                .filter(c -> ReviewCommentLevelEnum.WARNING.getCode().equals(c.getLevel()))
                .count();
        long infoCount = comments.stream()
                .filter(c -> ReviewCommentLevelEnum.INFO.getCode().equals(c.getLevel()))
                .count();

        summary.setCriticalCount((int) criticalCount);
        summary.setWarningCount((int) warningCount);
        summary.setInfoCount((int) infoCount);
        summary.setHasCritical(criticalCount > 0);

        // 统计已提交评审人数
        int totalReviewers = session.getReviewers() != null ? session.getReviewers().size() : 0;
        long submittedCount = session.getReviewers() != null
                ? session.getReviewers().stream()
                .filter(Map.class::isInstance)
                .map(r -> (Map<String, Object>) r)
                .filter(r -> isSubmittedStatus(String.valueOf(r.get("status"))))
                .count()
                : 0;

        summary.setTotalReviewers(totalReviewers);
        summary.setSubmittedCount((int) submittedCount);

        return summary;
    }

    private void archivePendingReviewMessages(ReviewSession session) {
        WorkflowNodeExecution nodeExecution = findWorkflowNodeExecution(session);
        if (nodeExecution == null || !StringUtils.hasText(nodeExecution.getId())) {
            return;
        }
        inAppMessageService.archiveBySource("workflow_human_review", nodeExecution.getId());
    }

    private void enrichMetadata(ReviewSession session, ReviewSessionVO vo) {
        if (session == null || vo == null) {
            return;
        }
        Spec spec = StringUtils.hasText(session.getSpecId()) ? specMapper.selectById(session.getSpecId()) : null;
        if (spec != null) {
            vo.setSpecName(spec.getName());
        }
        String ownerId = StringUtils.hasText(session.getOwner())
                ? session.getOwner()
                : spec != null ? spec.getOwner() : null;
        vo.setOwnerName(resolveUserDisplayName(ownerId));

        WorkflowNodeExecution nodeExecution = findWorkflowNodeExecution(session);
        if (nodeExecution != null && StringUtils.hasText(nodeExecution.getNodeLabel())) {
            vo.setWorkflowNodeLabel(nodeExecution.getNodeLabel());
        }
    }

    private void enrichApproverSnapshot(ReviewSessionVO vo) {
        if (vo == null || vo.getReviewers() == null || vo.getReviewers().isEmpty()) {
            return;
        }
        vo.getReviewers().stream()
                .filter(item -> Set.of("approved", "rejected", "request_modify").contains(stringValue(item.get("status"))))
                .max((left, right) -> {
                    LocalDateTime leftAt = parseDateTime(left.get("submittedAt"));
                    LocalDateTime rightAt = parseDateTime(right.get("submittedAt"));
                    if (leftAt == null && rightAt == null) {
                        return 0;
                    }
                    if (leftAt == null) {
                        return -1;
                    }
                    if (rightAt == null) {
                        return 1;
                    }
                    return leftAt.compareTo(rightAt);
                })
                .ifPresent(item -> {
                    vo.setApproverId(stringValue(item.get("userId")));
                    vo.setApproverName(stringValue(item.get("reviewerName")));
                    vo.setApproverAt(parseDateTime(item.get("submittedAt")));
                });
    }

    private WorkflowNodeExecution findWorkflowNodeExecution(ReviewSession session) {
        if (session == null || !StringUtils.hasText(session.getWorkflowInstanceId())
                || !StringUtils.hasText(session.getWorkflowNodeId())) {
            return null;
        }
        return workflowNodeExecutionMapper.selectOne(
                new LambdaQueryWrapper<WorkflowNodeExecution>()
                        .eq(WorkflowNodeExecution::getInstanceId, session.getWorkflowInstanceId())
                        .eq(WorkflowNodeExecution::getNodeId, session.getWorkflowNodeId())
                        .orderByDesc(WorkflowNodeExecution::getCreatedAt)
                        .last("LIMIT 1")
        );
    }

    private String resolveUserDisplayName(String userId) {
        return resolveUserDisplayName(userId, null);
    }

    private String resolveUserDisplayName(String userId, Map<String, User> cache) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        User user = cache != null ? cache.get(userId) : userMapper.selectById(userId);
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

    /**
     * 更新评审人状态
     */
    @SuppressWarnings("unchecked")
    private void updateReviewerStatus(ReviewSession session, String userId, String status) {
        if (session.getReviewers() == null) {
            return;
        }

        List<Object> updatedReviewers = session.getReviewers().stream()
                .map(r -> {
                    if (r instanceof Map<?, ?> rawMap) {
                        Map<String, Object> reviewer = new HashMap<>((Map<String, Object>) rawMap);
                        if (userId.equals(reviewer.get("userId"))) {
                            if (!StringUtils.hasText(stringValue(reviewer.get("reviewerName")))) {
                                reviewer.put("reviewerName", resolveUserDisplayName(userId));
                            }
                            reviewer.put("status", status);
                            reviewer.put("submittedAt", LocalDateTime.now().toString());
                        }
                        return (Object) reviewer;
                    }
                    return r;
                })
                .toList();

        session.setReviewers(updatedReviewers);
        sessionMapper.updateById(session);
    }

    /**
     * 检查当前用户是否是会话的评审人
     */
    @SuppressWarnings("unchecked")
    private boolean isReviewerInSession(ReviewSession session, String userId) {
        if (session.getReviewers() == null) {
            return false;
        }
        return session.getReviewers().stream()
                .filter(Map.class::isInstance)
                .map(r -> (Map<String, Object>) r)
                .anyMatch(r -> userId.equals(r.get("userId")));
    }

    /**
     * 检查是否所有评审人都已提交
     */
    @SuppressWarnings("unchecked")
    private boolean isAllReviewersSubmitted(ReviewSession session) {
        if (session.getReviewers() == null || session.getReviewers().isEmpty()) {
            return false;
        }
        return session.getReviewers().stream()
                .filter(Map.class::isInstance)
                .map(r -> (Map<String, Object>) r)
                .allMatch(r -> "submitted".equals(r.get("status")));
    }

    private boolean isSubmittedStatus(String status) {
        return StringUtils.hasText(status)
                && !ReviewSessionStatusEnum.PENDING.getCode().equals(status)
                && !"pending".equals(status);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private LocalDateTime parseDateTime(Object value) {
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

    /**
     * 聚合评审决策（多数决）
     */
    @SuppressWarnings("unchecked")
    private String aggregateDecision(ReviewSession session) {
        if (session.getReviewers() == null || session.getReviewers().isEmpty()) {
            return "approved";
        }
        long approveCount = session.getReviewers().stream()
                .filter(Map.class::isInstance)
                .map(r -> (Map<String, Object>) r)
                .filter(r -> "approved".equals(r.get("status")) || "submitted".equals(r.get("status")))
                .count();
        return approveCount > session.getReviewers().size() / 2 ? "approved" : "rejected";
    }

    /**
     * 查找未提交评审的评审人 ID 列表
     */
    @SuppressWarnings("unchecked")
    private List<String> findPendingReviewerIds(ReviewSession session) {
        if (session.getReviewers() == null) {
            return List.of();
        }
        return session.getReviewers().stream()
                .filter(Map.class::isInstance)
                .map(r -> (Map<String, Object>) r)
                .filter(r -> !"submitted".equals(r.get("status")))
                .map(r -> stringValue(r.get("userId")))
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 解析超时升级通知的接收人（owner + 租户管理员）
     */
    private List<String> resolveEscalateRecipients(ReviewSession session) {
        List<String> recipients = new ArrayList<>();
        if (StringUtils.hasText(session.getOwner())) {
            recipients.add(session.getOwner());
        }

        // 查找租户管理员：先找 admin 角色 ID，再通过 UserRole 关联查用户
        Role adminRole = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>()
                        .eq(Role::getTenantId, session.getTenantId())
                        .eq(Role::getCode, ReviewNotificationConstant.ADMIN_ROLE_CODE)
                        .last("LIMIT 1"));
        if (adminRole != null) {
            List<String> adminUserIds = userRoleMapper.selectList(
                    new LambdaQueryWrapper<UserRole>()
                            .eq(UserRole::getRoleId, adminRole.getId()))
                    .stream()
                    .map(UserRole::getUserId)
                    .collect(Collectors.toList());
            recipients.addAll(adminUserIds);
        }

        return recipients.stream().distinct().collect(Collectors.toList());
    }
}
