package com.schemaplexai.service.integration.git;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitOperationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void commitChangesShouldFallbackToCliForGitWorktree() throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git 命令不可用，跳过 worktree 提交测试");

        Path sourceRepo = tempDir.resolve("source");
        Path bareMirror = tempDir.resolve("mirror.git");
        Path worktree = tempDir.resolve("worktree");

        run(tempDir, "git", "init", "--initial-branch=main", sourceRepo.toString());
        Files.writeString(sourceRepo.resolve("README.md"), "# demo\n", StandardCharsets.UTF_8);
        run(sourceRepo, "git", "add", "README.md");
        run(
                sourceRepo,
                "git",
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.com",
                "commit",
                "-m",
                "init"
        );

        run(tempDir, "git", "clone", "--bare", sourceRepo.toString(), bareMirror.toString());
        run(bareMirror, "git", "worktree", "add", "-b", "feature/test", worktree.toString(), "main");

        Files.createDirectories(worktree.resolve("docs"));
        Files.writeString(worktree.resolve("docs/output.md"), "# artifact\n", StandardCharsets.UTF_8);

        GitOperationService service = new GitOperationService();
        boolean committed = service.commitChanges(worktree.toString(), "docs: add artifact");

        assertThat(committed).isTrue();
        assertThat(run(worktree, "git", "log", "-1", "--pretty=%s")).isEqualTo("docs: add artifact");
    }

    @Test
    void commitChangesShouldForceAddIgnoredArtifactPaths() throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git 命令不可用，跳过 worktree 提交测试");

        Path sourceRepo = tempDir.resolve("ignored-source");
        Path bareMirror = tempDir.resolve("ignored-mirror.git");
        Path worktree = tempDir.resolve("ignored-worktree");

        run(tempDir, "git", "init", "--initial-branch=main", sourceRepo.toString());
        Files.writeString(sourceRepo.resolve("README.txt"), "demo\n", StandardCharsets.UTF_8);
        Files.writeString(sourceRepo.resolve(".gitignore"), "*.md\n", StandardCharsets.UTF_8);
        run(sourceRepo, "git", "add", "README.txt", ".gitignore");
        run(
                sourceRepo,
                "git",
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.com",
                "commit",
                "-m",
                "init"
        );

        run(tempDir, "git", "clone", "--bare", sourceRepo.toString(), bareMirror.toString());
        run(bareMirror, "git", "worktree", "add", "-b", "feature/ignored-artifact", worktree.toString(), "main");

        Files.createDirectories(worktree.resolve("docs"));
        Files.writeString(worktree.resolve("docs/output.md"), "# ignored artifact\n", StandardCharsets.UTF_8);

        GitOperationService service = new GitOperationService();
        boolean committed = service.commitChanges(
                worktree.toString(),
                "docs: force add ignored artifact",
                List.of("docs/output.md")
        );

        assertThat(committed).isTrue();
        assertThat(run(worktree, "git", "log", "-1", "--pretty=%s")).isEqualTo("docs: force add ignored artifact");
        assertThat(run(worktree, "git", "ls-files", "docs/output.md")).isEqualTo("docs/output.md");
    }

    @Test
    void inspectWorkspaceChangesShouldSeparateImplementationFilesFromDocs() throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git 命令不可用，跳过工作树变更检测测试");

        Path repo = tempDir.resolve("status-source");
        run(tempDir, "git", "init", "--initial-branch=main", repo.toString());
        Files.createDirectories(repo.resolve("src/main/java/com/example"));
        Files.writeString(repo.resolve("src/main/java/com/example/App.java"), "class App {}\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("README.md"), "# demo\n", StandardCharsets.UTF_8);
        run(repo, "git", "add", "src/main/java/com/example/App.java", "README.md");
        run(
                repo,
                "git",
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.com",
                "commit",
                "-m",
                "init"
        );

        Files.writeString(repo.resolve("src/main/java/com/example/App.java"), "class App { void run() {} }\n", StandardCharsets.UTF_8);
        Files.createDirectories(repo.resolve("docs"));
        Files.writeString(repo.resolve("docs/output.md"), "# artifact\n", StandardCharsets.UTF_8);

        GitOperationService service = new GitOperationService();
        GitOperationService.WorkspaceChangeSummary summary = service.inspectWorkspaceChanges(repo.toString());

        assertThat(summary.clean()).isFalse();
        assertThat(summary.changedFiles()).contains("src/main/java/com/example/App.java", "docs/output.md");
        assertThat(summary.implementationFiles()).containsExactly("src/main/java/com/example/App.java");
        assertThat(summary.totalChangedFiles()).isEqualTo(2);
        assertThat(summary.implementationChangedFileCount()).isEqualTo(1);
    }

    private boolean isGitAvailable() {
        try {
            run(tempDir, "git", "--version");
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private String run(Path workingDirectory, String... command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("命令执行失败: " + String.join(" ", command) + System.lineSeparator() + output);
        }
        return output.trim();
    }
}
