package com.schemaplexai.service.integration.git;

import com.schemaplexai.service.workspace.WorkspacePathResolver;
import com.schemaplexai.service.workspace.WorkspaceSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitWorkspaceOrchestratorTest {

    @TempDir
    Path tempDir;

    @Test
    void prepareSnapshotWorkspaceFromSourceShouldCopySourceIntoManagedDirectory() throws Exception {
        Path sourceRoot = tempDir.resolve("source");
        Files.createDirectories(sourceRoot.resolve("schemaplexai-server/src/main/java"));
        Files.createDirectories(sourceRoot.resolve("node_modules/demo"));
        Files.writeString(sourceRoot.resolve("schemaplexai-server/src/main/java/App.java"), "class App {}\n", StandardCharsets.UTF_8);
        Files.writeString(sourceRoot.resolve("README.md"), "# demo\n", StandardCharsets.UTF_8);
        Files.writeString(sourceRoot.resolve("node_modules/demo/index.js"), "console.log('skip');\n", StandardCharsets.UTF_8);

        Path managedRoot = tempDir.resolve("managed/workspace-root");
        WorkspacePathResolver workspacePathResolver = mock(WorkspacePathResolver.class);
        when(workspacePathResolver.resolveWorkspacePath("tenant-1", "workspace-1")).thenReturn(managedRoot);

        GitOperationService gitOperationService = new GitOperationService();
        WorkspaceSessionService workspaceSessionService = new WorkspaceSessionService();
        GitWorkspaceOrchestrator orchestrator = new GitWorkspaceOrchestrator(
                gitOperationService,
                workspacePathResolver,
                workspaceSessionService
        );
        ReflectionTestUtils.setField(orchestrator, "sessionTtlMinutes", 30);
        ReflectionTestUtils.setField(orchestrator, "defaultBaseBranch", "main");

        WorkspaceSessionService.WorkspaceSession session = orchestrator.prepareSnapshotWorkspaceFromSource(
                "tenant-1",
                "workspace-1",
                "user-1",
                "exec-1",
                sourceRoot.toString(),
                "feature/demo"
        );

        Path snapshotRoot = Path.of(session.getWorktreePath());
        assertThat(session.getSessionMode()).isEqualTo(WorkspaceSessionService.SessionMode.SNAPSHOT_COPY);
        assertThat(session.getBranchName()).isEqualTo("feature/demo");
        assertThat(snapshotRoot).startsWith(managedRoot.resolve(".snapshots"));
        assertThat(snapshotRoot.resolve("README.md")).hasContent("# demo\n");
        assertThat(snapshotRoot.resolve("schemaplexai-server/src/main/java/App.java")).hasContent("class App {}\n");
        assertThat(snapshotRoot.resolve("node_modules")).doesNotExist();
    }
}
