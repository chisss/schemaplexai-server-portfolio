package com.schemaplexai.service.integration.git;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.service.workspace.WorkspacePathResolver;
import com.schemaplexai.service.workspace.WorkspaceSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;

/**
 * Git工作空间编排器（基于worktree的多用户隔离方案）
 * 负责创建和管理每个用户独立的worktree分支。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitWorkspaceOrchestrator {

    private final GitOperationService gitOperationService;
    private final WorkspacePathResolver workspacePathResolver;
    private final WorkspaceSessionService workspaceSessionService;

    @Value("${schemaplexai.workspace.session.ttl-minutes:120}")
    private int sessionTtlMinutes;

    @Value("${schemaplexai.workspace.base-branch:main}")
    private String defaultBaseBranch;

    /**
     * 为指定execution准备隔离工作空间
     *
     * @param tenantId    租户ID
     * @param workspaceId 工作空间ID
     * @param userId      用户ID
     * @param executionId 执行ID
     * @param baseBranch  基础分支（可为空使用默认）
     * @return 工作空间会话
     */
    public WorkspaceSessionService.WorkspaceSession prepareIsolatedWorkspace(String tenantId,
                                                                             String workspaceId,
                                                                             String userId,
                                                                             String executionId,
                                                                             String baseBranch) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(workspaceId) || !StringUtils.hasText(executionId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "隔离工作空间参数不完整");
        }

        Path workspaceRoot = workspacePathResolver.resolveWorkspacePath(tenantId, workspaceId);
        Path mirrorPath = workspaceRoot.resolve(".mirror.git");
        Path worktreePath = workspaceRoot.resolve(".worktrees").resolve(executionId);
        String branchName = buildExecutionBranch(userId, executionId);
        String startBranch = StringUtils.hasText(baseBranch) ? baseBranch : defaultBaseBranch;

        try {
            gitOperationService.ensureBareMirror(workspaceRoot.toString(), mirrorPath.toString());
            gitOperationService.addWorktree(
                    mirrorPath.toString(),
                    branchName,
                    worktreePath.toString(),
                    startBranch
            );
            return workspaceSessionService.openSession(
                    tenantId,
                    workspaceId,
                    userId,
                    executionId,
                    branchName,
                    worktreePath.toString(),
                    sessionTtlMinutes
            );
        } catch (Exception e) {
            log.error("创建隔离工作区失败: tenantId={}, workspaceId={}, executionId={}",
                    tenantId, workspaceId, executionId, e);
            throw new BusinessException(ResultCode.WORKSPACE_CLONE_FAILED, e.getMessage());
        }
    }

    /**
     * 清理指定会话的工作区资源
     */
    public void cleanupSession(WorkspaceSessionService.WorkspaceSession session) {
        if (session == null) {
            return;
        }
        try {
            Path workspaceRoot = workspacePathResolver.resolveWorkspacePath(session.getTenantId(), session.getWorkspaceId());
            Path mirrorPath = workspaceRoot.resolve(".mirror.git");
            gitOperationService.removeWorktree(mirrorPath.toString(), session.getWorktreePath());
        } catch (Exception e) {
            log.warn("清理会话工作区失败: sessionId={}, worktreePath={}",
                    session.getSessionId(), session.getWorktreePath(), e);
        } finally {
            workspaceSessionService.closeSession(session.getSessionId());
        }
    }

    /**
     * 根据executionId清理所有关联会话
     */
    public void cleanupByExecutionId(String executionId) {
        for (WorkspaceSessionService.WorkspaceSession session : workspaceSessionService.listByExecutionId(executionId)) {
            cleanupSession(session);
        }
    }

    /**
     * 构建execution专属分支名
     * 格式: agent/{userId}/{executionId}
     */
    private String buildExecutionBranch(String userId, String executionId) {
        String normalizedUser = StringUtils.hasText(userId)
                ? userId.replaceAll("[^a-zA-Z0-9_-]", "_")
                : "system";
        String normalizedExec = executionId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return "agent/" + normalizedUser + "/" + normalizedExec;
    }
}
