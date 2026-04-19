package com.schemaplexai.service.workspace;

import com.schemaplexai.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkspacePathResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldAllowPathInsideWorkspaceRoot() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspaces"));
        WorkspacePathResolver resolver = new WorkspacePathResolver(workspaceRoot.toString());
        Path docsPath = Files.createDirectories(
                workspaceRoot.resolve("tenant-a").resolve("workspace-a").resolve("docs")
        );

        Path actual = resolver.validateWithinWorkspaceRoot(docsPath);

        assertThat(actual).isEqualTo(docsPath.toRealPath());
    }

    @Test
    void shouldRejectSymlinkEscapePath() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspaces"));
        Path outsideRoot = Files.createDirectories(tempDir.resolve("outside"));
        Path workspacePath = Files.createDirectories(workspaceRoot.resolve("tenant-a").resolve("workspace-a"));
        Path link = workspacePath.resolve("escape");
        createSymbolicLinkOrSkip(link, outsideRoot);
        WorkspacePathResolver resolver = new WorkspacePathResolver(workspaceRoot.toString());

        assertThatThrownBy(() -> resolver.validateWithinWorkspaceRoot(link.resolve("secret.txt")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("工作空间路径必须位于工作空间根目录内");
    }

    private void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException exception) {
            assumeTrue(false, "当前环境不支持符号链接测试");
        }
    }
}
