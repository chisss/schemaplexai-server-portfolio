package com.schemaplexai.service.workspace;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作空间会话管理服务
 * 管理execution与user的worktree分支映射关系。
 */
@Slf4j
@Service
public class WorkspaceSessionService {

    private final ConcurrentHashMap<String, WorkspaceSession> sessions = new ConcurrentHashMap<>();

    /**
     * 开启工作空间会话
     */
    public WorkspaceSession openSession(String tenantId,
                                        String workspaceId,
                                        String userId,
                                        String executionId,
                                        String branchName,
                                        String worktreePath,
                                        int ttlMinutes) {
        return openSession(tenantId, workspaceId, userId, executionId, branchName, worktreePath, ttlMinutes, SessionMode.GIT_WORKTREE);
    }

    /**
     * 开启工作空间会话
     */
    public WorkspaceSession openSession(String tenantId,
                                        String workspaceId,
                                        String userId,
                                        String executionId,
                                        String branchName,
                                        String worktreePath,
                                        int ttlMinutes,
                                        SessionMode sessionMode) {
        WorkspaceSession session = WorkspaceSession.builder()
                .sessionId(UUID.randomUUID().toString().replace("-", ""))
                .tenantId(tenantId)
                .workspaceId(workspaceId)
                .userId(userId)
                .executionId(executionId)
                .branchName(branchName)
                .worktreePath(worktreePath)
                .createdAt(LocalDateTime.now())
                .lastHeartbeatAt(LocalDateTime.now())
                .expireAt(LocalDateTime.now().plusMinutes(Math.max(ttlMinutes, 5)))
                .sessionMode(sessionMode != null ? sessionMode : SessionMode.GIT_WORKTREE)
                .status(SessionStatus.ACTIVE)
                .build();
        sessions.put(session.getSessionId(), session);
        log.info("创建工作空间会话: sessionId={}, executionId={}, workspaceId={}",
                session.getSessionId(), executionId, workspaceId);
        return session;
    }

    /**
     * 根据sessionId获取会话
     */
    public WorkspaceSession getSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sessions.get(sessionId);
    }

    /**
     * 根据executionId获取会话
     */
    public WorkspaceSession getByExecutionId(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return null;
        }
        for (WorkspaceSession session : sessions.values()) {
            if (executionId.equals(session.getExecutionId())) {
                return session;
            }
        }
        return null;
    }

    /**
     * 根据executionId获取所有会话
     */
    public List<WorkspaceSession> listByExecutionId(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return List.of();
        }
        List<WorkspaceSession> result = new ArrayList<>();
        for (WorkspaceSession session : sessions.values()) {
            if (executionId.equals(session.getExecutionId())) {
                result.add(session);
            }
        }
        return result;
    }

    /**
     * 关闭指定会话
     */
    public void closeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        WorkspaceSession removed = sessions.remove(sessionId);
        if (removed != null) {
            removed.setStatus(SessionStatus.CLOSED);
            log.info("关闭工作空间会话: sessionId={}, executionId={}", sessionId, removed.getExecutionId());
        }
    }

    /**
     * 根据executionId关闭所有会话
     */
    public void closeByExecutionId(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return;
        }
        List<String> targets = new ArrayList<>();
        for (WorkspaceSession session : sessions.values()) {
            if (executionId.equals(session.getExecutionId())) {
                targets.add(session.getSessionId());
            }
        }
        for (String sessionId : targets) {
            closeSession(sessionId);
        }
    }

    /**
     * 心跳续期
     */
    public void heartbeat(String sessionId, int ttlMinutes) {
        WorkspaceSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        session.setLastHeartbeatAt(LocalDateTime.now());
        session.setExpireAt(LocalDateTime.now().plusMinutes(Math.max(ttlMinutes, 5)));
    }

    /**
     * 标记会话已清理
     */
    public void markCleaned(String sessionId) {
        WorkspaceSession session = sessions.get(sessionId);
        if (session != null) {
            session.setStatus(SessionStatus.CLEANED);
        }
    }

    /**
     * 列出所有已过期的会话
     */
    public List<WorkspaceSession> listExpiredSessions() {
        LocalDateTime now = LocalDateTime.now();
        List<WorkspaceSession> expired = new ArrayList<>();
        for (WorkspaceSession session : sessions.values()) {
            if (session.getStatus() == SessionStatus.ACTIVE
                    && session.getExpireAt() != null
                    && session.getExpireAt().isBefore(now)) {
                expired.add(session);
            }
        }
        return expired;
    }

    @Data
    @Builder
    public static class WorkspaceSession {
        private String sessionId;
        private String tenantId;
        private String workspaceId;
        private String userId;
        private String executionId;
        private String branchName;
        private String worktreePath;
        private LocalDateTime createdAt;
        private LocalDateTime lastHeartbeatAt;
        private LocalDateTime expireAt;
        private SessionMode sessionMode;
        private SessionStatus status;
    }

    public enum SessionMode {
        GIT_WORKTREE,
        SNAPSHOT_COPY
    }

    public enum SessionStatus {
        ACTIVE,
        CLOSED,
        CLEANED
    }
}
