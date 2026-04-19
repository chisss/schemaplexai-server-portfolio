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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/**
 * Git工作空间编排器（基于worktree的多用户隔离方案）
 * 负责创建和管理每个用户独立的worktree分支。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitWorkspaceOrchestrator {

    private static final Set<String> SNAPSHOT_EXCLUDED_NAMES = Set.of(
            ".git", ".mirror.git", ".worktrees", ".snapshots",
            "node_modules", "target", "dist", "build",
            ".idea", ".serena", ".spec-workflow", ".DS_Store"
    );

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
        return prepareIsolatedWorkspace(tenantId, workspaceId, userId, executionId, baseBranch, null);
    }

    public WorkspaceSessionService.WorkspaceSession prepareIsolatedWorkspace(String tenantId,
                                                                             String workspaceId,
                                                                             String userId,
                                                                             String executionId,
                                                                             String baseBranch,
                                                                             String preferredBranch) {
        Path workspaceRoot = workspacePathResolver.resolveWorkspacePath(tenantId, workspaceId);
        return prepareIsolatedWorkspaceFromSource(
                tenantId,
                workspaceId,
                userId,
                executionId,
                workspaceRoot.toString(),
                baseBranch,
                preferredBranch
        );
    }

    public WorkspaceSessionService.WorkspaceSession prepareIsolatedWorkspaceFromSource(String tenantId,
                                                                                       String workspaceId,
                                                                                       String userId,
                                                                                       String executionId,
                                                                                       String sourceRepoPath,
                                                                                       String baseBranch,
                                                                                       String preferredBranch) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(workspaceId) || !StringUtils.hasText(executionId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "隔离工作空间参数不完整");
        }
        if (!StringUtils.hasText(sourceRepoPath)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "隔离工作空间缺少源码路径");
        }

        Path workspaceRoot = workspacePathResolver.resolveWorkspacePath(tenantId, workspaceId);
        Path mirrorPath = workspaceRoot.resolve(".mirror.git");
        Path worktreePath = workspaceRoot.resolve(".worktrees").resolve(executionId);
        String branchName = StringUtils.hasText(preferredBranch)
                ? preferredBranch.trim()
                : buildExecutionBranch(userId, executionId);
        String startBranch = StringUtils.hasText(baseBranch) ? baseBranch : defaultBaseBranch;

        try {
            gitOperationService.ensureBareMirror(sourceRepoPath, mirrorPath.toString());
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
     * 为任意本地目录准备受控快照工作空间。
     * 当源码目录不是独立 Git 根目录时，仍然可以提供位于沙箱根目录内的可操作副本。
     */
    public WorkspaceSessionService.WorkspaceSession prepareSnapshotWorkspaceFromSource(String tenantId,
                                                                                       String workspaceId,
                                                                                       String userId,
                                                                                       String executionId,
                                                                                       String sourcePath,
                                                                                       String preferredBranch) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(workspaceId) || !StringUtils.hasText(executionId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "快照工作空间参数不完整");
        }
        if (!StringUtils.hasText(sourcePath)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "快照工作空间缺少源码路径");
        }

        Path sourceRoot = Path.of(sourcePath).toAbsolutePath().normalize();
        if (!Files.isDirectory(sourceRoot)) {
            throw new BusinessException(ResultCode.WORKSPACE_NOT_FOUND, "本地源码目录不存在: " + sourceRoot);
        }

        Path workspaceRoot = workspacePathResolver.resolveWorkspacePath(tenantId, workspaceId);
        Path snapshotPath = workspaceRoot.resolve(".snapshots").resolve(executionId);
        String branchName = StringUtils.hasText(preferredBranch)
                ? preferredBranch.trim()
                : buildExecutionBranch(userId, executionId);

        try {
            gitOperationService.deleteDirectory(snapshotPath.toString());
            copySnapshot(sourceRoot, snapshotPath);
            return workspaceSessionService.openSession(
                    tenantId,
                    workspaceId,
                    userId,
                    executionId,
                    branchName,
                    snapshotPath.toString(),
                    sessionTtlMinutes,
                    WorkspaceSessionService.SessionMode.SNAPSHOT_COPY
            );
        } catch (IOException exception) {
            log.error("创建快照工作区失败: tenantId={}, workspaceId={}, executionId={}, sourcePath={}",
                    tenantId, workspaceId, executionId, sourcePath, exception);
            throw new BusinessException(ResultCode.WORKSPACE_CLONE_FAILED, exception.getMessage());
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
            if (session.getSessionMode() == WorkspaceSessionService.SessionMode.SNAPSHOT_COPY) {
                gitOperationService.deleteDirectory(session.getWorktreePath());
            } else {
                Path workspaceRoot = workspacePathResolver.resolveWorkspacePath(session.getTenantId(), session.getWorkspaceId());
                Path mirrorPath = workspaceRoot.resolve(".mirror.git");
                gitOperationService.removeWorktree(mirrorPath.toString(), session.getWorktreePath());
            }
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

    private void copySnapshot(Path sourceRoot, Path snapshotRoot) throws IOException {
        Files.createDirectories(snapshotRoot);
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!sourceRoot.equals(dir) && shouldSkipSnapshotPath(sourceRoot.relativize(dir))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path relative = sourceRoot.relativize(dir);
                Path targetDir = relative.getNameCount() == 0 ? snapshotRoot : snapshotRoot.resolve(relative);
                Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink()) {
                    log.debug("快照工作区跳过符号链接: path={}", file);
                    return FileVisitResult.CONTINUE;
                }
                Path relative = sourceRoot.relativize(file);
                if (shouldSkipSnapshotPath(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                Path targetFile = snapshotRoot.resolve(relative);
                Files.createDirectories(targetFile.getParent());
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean shouldSkipSnapshotPath(Path relativePath) {
        for (Path segment : relativePath) {
            if (SNAPSHOT_EXCLUDED_NAMES.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }
}
