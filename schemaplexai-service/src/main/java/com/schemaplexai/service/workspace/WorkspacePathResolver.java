package com.schemaplexai.service.workspace;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
        Path normalizedPath = inputPath.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(workspaceRoot)) {
            throw new BusinessException(ResultCode.WORKSPACE_PATH_CONFLICT, "工作空间路径必须位于工作空间根目录内");
        }
        return normalizedPath;
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }
}
