package com.schemaplexai.service.workflow.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.ReviewCommentLevelEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.ReviewCommentMapper;
import com.schemaplexai.dao.mapper.ReviewSessionMapper;
import com.schemaplexai.model.converter.ReviewCommentConverter;
import com.schemaplexai.model.converter.ReviewSessionConverter;
import com.schemaplexai.model.dto.workflow.ReviewCommentCreateRequest;
import com.schemaplexai.model.dto.workflow.ReviewSessionCreateRequest;
import com.schemaplexai.model.entity.ReviewComment;
import com.schemaplexai.model.entity.ReviewSession;
import com.schemaplexai.model.vo.workflow.ReviewCommentVO;
import com.schemaplexai.model.vo.workflow.ReviewSessionVO;
import com.schemaplexai.model.vo.workflow.ReviewSummaryVO;
import com.schemaplexai.service.workflow.ReviewSessionService;
import com.schemaplexai.service.workflow.validator.ReviewValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewSessionVO create(ReviewSessionCreateRequest request) {
        var session = new ReviewSession();
        session.setTenantId(SecurityUtil.getCurrentTenantId());
        session.setSpecId(request.getSpecId());
        session.setDocumentType(request.getDocumentType());
        session.setOwner(SecurityUtil.getCurrentUserId());

        // 构建评审人列表，每人初始状态为 pending
        List<Object> reviewers = request.getReviewers().stream()
                .map(r -> {
                    Map<String, Object> reviewer = new HashMap<>();
                    reviewer.put("userId", r.get("userId"));
                    reviewer.put("role", r.get("role"));
                    reviewer.put("status", "pending");
                    reviewer.put("submittedAt", null);
                    return (Object) reviewer;
                })
                .collect(Collectors.toList());
        session.setReviewers(reviewers);

        // 计算截止时间
        int hours = request.getDeadlineHours() != null ? request.getDeadlineHours() : 48;
        session.setDeadline(LocalDateTime.now().plusHours(hours));
        session.setTimeoutStrategy(request.getTimeoutStrategy());
        session.setStatus("pending");

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
        if ("pending".equals(session.getStatus())) {
            session.setStatus("in_progress");
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
    public List<ReviewSessionVO> getMyPending() {
        var currentUserId = SecurityUtil.getCurrentUserId();

        // 查询状态为 pending 或 in_progress 的会话
        var sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<ReviewSession>()
                        .in(ReviewSession::getStatus, "pending", "in_progress")
                        .orderByDesc(ReviewSession::getCreatedAt)
        );

        // 过滤当前用户是评审人的会话
        return sessions.stream()
                .filter(s -> isReviewerInSession(s, currentUserId))
                .map(this::enrichWithSummary)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void checkAndCompleteSession(String sessionId) {
        var session = sessionMapper.selectById(sessionId);
        if (session == null || "completed".equals(session.getStatus()) || "timeout".equals(session.getStatus())) {
            return;
        }

        // 检查所有评审人是否都已提交
        boolean allSubmitted = isAllReviewersSubmitted(session);
        if (allSubmitted) {
            session.setStatus("completed");
            sessionMapper.updateById(session);
            log.info("评审会话已完成: sessionId={}", sessionId);

            // TODO: 对接事件通知 — 发送评审完成事件（RabbitMQ）
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleTimeoutSessions() {
        // 查询已超时但未完成的会话
        var timeoutSessions = sessionMapper.selectList(
                new LambdaQueryWrapper<ReviewSession>()
                        .in(ReviewSession::getStatus, "pending", "in_progress")
                        .le(ReviewSession::getDeadline, LocalDateTime.now())
        );

        for (var session : timeoutSessions) {
            String strategy = session.getTimeoutStrategy();
            switch (strategy != null ? strategy : "escalate") {
                case "auto_pass":
                    // 自动通过：将会话标记为完成
                    session.setStatus("completed");
                    sessionMapper.updateById(session);
                    log.info("评审会话超时自动通过: sessionId={}", session.getId());
                    break;

                case "remind":
                    // 提醒未提交的评审人
                    // TODO: 对接通知服务 — 向未提交的评审人发送提醒
                    log.info("评审会话超时提醒: sessionId={}", session.getId());
                    break;

                case "escalate":
                default:
                    // 升级处理：标记为超时状态
                    session.setStatus("timeout");
                    sessionMapper.updateById(session);
                    log.info("评审会话超时升级: sessionId={}", session.getId());
                    // TODO: 对接通知服务 — 通知会话 owner 和管理员
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

    /**
     * 丰富会话 VO，包含评审意见和汇总信息
     */
    private ReviewSessionVO enrichWithSummary(ReviewSession session) {
        var vo = sessionConverter.toVO(session);

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
                .filter(r -> r instanceof Map)
                .map(r -> (Map<String, Object>) r)
                .filter(r -> "submitted".equals(r.get("status")))
                .count()
                : 0;

        summary.setTotalReviewers(totalReviewers);
        summary.setSubmittedCount((int) submittedCount);

        return summary;
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
                    if (r instanceof Map) {
                        Map<String, Object> reviewer = new HashMap<>((Map<String, Object>) r);
                        if (userId.equals(reviewer.get("userId"))) {
                            reviewer.put("status", status);
                            reviewer.put("submittedAt", LocalDateTime.now().toString());
                        }
                        return (Object) reviewer;
                    }
                    return r;
                })
                .collect(Collectors.toList());

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
                .filter(r -> r instanceof Map)
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
                .filter(r -> r instanceof Map)
                .map(r -> (Map<String, Object>) r)
                .allMatch(r -> "submitted".equals(r.get("status")));
    }
}
