package com.schemaplexai.service.workspace;

import com.schemaplexai.service.integration.git.GitWorkspaceOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工作空间会话清理任务
 * 定时清理过期的worktree会话，释放磁盘资源。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceCleanupService {

    private final WorkspaceSessionService workspaceSessionService;
    private final GitWorkspaceOrchestrator gitWorkspaceOrchestrator;

    /**
     * 定时清理过期会话（默认5分钟一次）
     */
    @Scheduled(fixedDelayString = "${schemaplexai.workspace.cleanup-interval-ms:300000}")
    public void cleanupExpiredSessions() {
        List<WorkspaceSessionService.WorkspaceSession> expiredSessions = workspaceSessionService.listExpiredSessions();
        if (expiredSessions.isEmpty()) {
            return;
        }
        log.info("开始清理过期工作空间会话，数量: {}", expiredSessions.size());
        for (WorkspaceSessionService.WorkspaceSession session : expiredSessions) {
            try {
                gitWorkspaceOrchestrator.cleanupSession(session);
                workspaceSessionService.markCleaned(session.getSessionId());
                log.info("清理过期工作空间会话成功: sessionId={}, executionId={}",
                        session.getSessionId(), session.getExecutionId());
            } catch (Exception e) {
                log.warn("清理过期工作空间会话失败: sessionId={}", session.getSessionId(), e);
            }
        }
    }
}
