package com.schemaplexai.service.workspace;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工作空间路径解析与安全校验
 */
@Component
public class WorkspacePathResolver {

    private final Path workspaceRoot;

    public WorkspacePathResolver(@Value("${schemaplexai.workspace.root-path:/data/workspaces}") String workspaceRootPath) {
        this.workspaceRoot = Path.of(workspaceRootPath).toAbsolutePath().normalize();
    }

    public Path resolveWorkspacePath(String tenantId, String workspaceId) {
        return validateWithinWorkspaceRoot(workspaceRoot.resolve(tenantId).resolve(workspaceId));
    }

    public Path validateWithinWorkspaceRoot(String inputPath) {
        if (!StringUtils.hasText(inputPath)) {
            throw new BusinessException(ResultCode.WORKSPACE_PATH_CONFLICT, "工作空间路径不能为空");
        }
        return validateWithinWorkspaceRoot(Path.of(inputPath));
    }

    public Path validateWithinWorkspaceRoot(Path inputPath) {
        if (inputPath == null) {
            throw new BusinessException(ResultCode.WORKSPACE_PATH_CONFLICT, "工作空间路径不能为空");
        }
        Path normalizedPath = inputPath.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(workspaceRoot)) {
            throw new BusinessException(ResultCode.WORKSPACE_PATH_CONFLICT, "工作空间路径必须位于工作空间根目录内");
        }
        Path verifiedPath = resolveAgainstExistingAncestor(normalizedPath);
        Path verifiedRoot = resolveExistingPath(workspaceRoot);
        if (!verifiedPath.startsWith(verifiedRoot)) {
            throw new BusinessException(ResultCode.WORKSPACE_PATH_CONFLICT, "工作空间路径必须位于工作空间根目录内");
        }
        return verifiedPath;
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    private Path resolveAgainstExistingAncestor(Path path) {
        Path existingAncestor = path;
        while (existingAncestor != null && !Files.exists(existingAncestor)) {
            existingAncestor = existingAncestor.getParent();
        }
        if (existingAncestor == null) {
            return path;
        }
        Path resolvedAncestor = resolveExistingPath(existingAncestor);
        if (existingAncestor.equals(path)) {
            return resolvedAncestor;
        }
        return resolvedAncestor.resolve(existingAncestor.relativize(path)).normalize();
    }

    private Path resolveExistingPath(Path path) {
        if (!Files.exists(path)) {
            return path.toAbsolutePath().normalize();
        }
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            throw new BusinessException(ResultCode.WORKSPACE_PATH_CONFLICT, "工作空间路径校验失败: " + path);
        }
    }
}
