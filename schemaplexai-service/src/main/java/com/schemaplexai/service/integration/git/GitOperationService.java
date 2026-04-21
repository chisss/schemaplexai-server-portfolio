package com.schemaplexai.service.integration.git;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.schemaplexai.model.vo.workspace.BranchDiffFileVO;
import com.schemaplexai.model.vo.workspace.WorkspaceBranchDiffVO;

/**
 * Git 操作服务
 * 封装 JGit 的 clone/pull/磁盘计算等操作
 */
@Slf4j
@Service
public class GitOperationService {

    private static final Set<String> DIFF_EXCLUDED_ROOTS = Set.of(".git", ".mirror.git", ".worktrees");
    private static final long COMMAND_TIMEOUT_SECONDS = 15L;

    @Value("${schemaplexai.workspace.root-path:/data/workspaces}")
    private String workspaceRootPath;

    /**
     * 克隆 Git 仓库到本地
     *
     * @param gitUrl        仓库地址
     * @param branch        默认分支
     * @param localPath     本地路径
     * @param credential    凭证信息 {type: "token", token: "xxx"} 或 {type: "password", username: "xxx", password: "xxx"}
     * @return 克隆后的 Git 对象
     */
    public Git cloneRepository(String gitUrl, String branch, String localPath, Map<String, Object> credential) throws GitAPIException {
        log.info("开始克隆仓库: url={}, branch={}, localPath={}", gitUrl, branch, localPath);
        File dir = prepareWorkspaceDirectory(localPath);

        CloneCommand cloneCommand = Git.cloneRepository()
                .setURI(gitUrl)
                .setDirectory(dir)
                .setCloneAllBranches(false)
                .setBranch(branch != null ? branch : "main");

        CredentialsProvider credProvider = buildCredentialsProvider(credential);
        if (credProvider != null) {
            cloneCommand.setCredentialsProvider(credProvider);
        }

        Git git = cloneCommand.call();
        log.info("仓库克隆成功: url={}, localPath={}", gitUrl, localPath);
        return git;
    }

    /**
     * 准备工作空间目录（必须可创建且可写）
     */
    private File prepareWorkspaceDirectory(String localPath) {
        Path workspacePath = Path.of(localPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(workspacePath);
            if (!Files.isDirectory(workspacePath)) {
                throw new IllegalStateException("工作空间路径不是目录: " + workspacePath);
            }
            if (!Files.isWritable(workspacePath)) {
                throw new IllegalStateException("工作空间路径无写权限: " + workspacePath);
            }
            return workspacePath.toFile();
        } catch (AccessDeniedException e) {
            throw new IllegalStateException("无法写入工作空间目录，请检查 WORKSPACE_ROOT 配置及目录权限: " + workspacePath, e);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建工作空间目录: " + workspacePath, e);
        }
    }

    /**
     * 拉取远程最新代码（git pull）
     *
     * @param localPath     本地仓库路径
     * @param credential    凭证信息
     * @return PullResult
     */
    public PullResult pull(String localPath, Map<String, Object> credential) throws GitAPIException, IOException {
        log.info("开始拉取更新: localPath={}", localPath);
        try (Git git = Git.open(new File(localPath))) {
            var pullCommand = git.pull();
            CredentialsProvider credProvider = buildCredentialsProvider(credential);
            if (credProvider != null) {
                pullCommand.setCredentialsProvider(credProvider);
            }
            PullResult result = pullCommand.call();
            log.info("拉取完成: localPath={}, success={}", localPath, result.isSuccessful());
            return result;
        }
    }

    /**
     * 计算目录的磁盘占用大小（MB）
     */
    public long calculateDiskUsageMb(String directoryPath) {
        Path path = Path.of(directoryPath);
        if (!Files.exists(path)) {
            return 0L;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            long totalBytes = walk
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
            return totalBytes / (1024 * 1024);
        } catch (IOException e) {
            log.warn("计算磁盘占用失败: path={}", directoryPath, e);
            return 0L;
        }
    }

    /**
     * 删除本地仓库目录
     */
    public void deleteDirectory(String directoryPath) {
        Path path = Path.of(directoryPath);
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            log.info("本地目录已清理: path={}", directoryPath);
        } catch (IOException e) {
            log.error("清理本地目录失败: path={}", directoryPath, e);
        }
    }

    // =========================================================================
    //  Worktree 操作（支持多用户并发隔离）
    // =========================================================================

    /**
     * 创建裸仓库镜像（用于worktree模式）
     *
     * @param mirrorPath 裸仓库路径
     * @return 裸仓库Git对象
     */
    public Git createBareMirror(String sourcePath, String mirrorPath) throws GitAPIException, IOException {
        Path path = Path.of(mirrorPath);
        File dir = path.toFile();
        if (dir.exists() && dir.listFiles() != null && dir.listFiles().length > 0) {
            log.info("裸仓库已存在，直接打开: mirrorPath={}", mirrorPath);
            return Git.open(dir);
        }

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法创建裸仓库目录: " + mirrorPath, e);
        }

        Git git = Git.cloneRepository()
                .setURI(Path.of(sourcePath).toUri().toString())
                .setDirectory(dir)
                .setBare(true)
                .setCloneAllBranches(true)
                .call();
        log.info("裸仓库创建成功: mirrorPath={}", mirrorPath);
        return git;
    }

    /**
     * 打开已有裸仓库
     */
    public Git openBareMirror(String mirrorPath) throws IOException {
        File dir = new File(mirrorPath);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalStateException("裸仓库不存在: " + mirrorPath);
        }
        return Git.open(dir);
    }

    /**
     * 添加工作树
     *
     * @param mirrorPath   裸仓库路径
     * @param branchName   分支名
     * @param worktreePath 工作树路径
     * @param startBranch 起始分支（为空则从当前分支创建）
     */
    public void addWorktree(String mirrorPath, String branchName, String worktreePath, String startBranch) throws GitAPIException {
        log.info("创建工作树: mirrorPath={}, branch={}, worktree={}", mirrorPath, branchName, worktreePath);
        try {
            Path wtPath = Path.of(worktreePath);
            Files.createDirectories(wtPath);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建工作树目录: " + worktreePath, e);
        }

        // 通过 shell 命令执行 git worktree add
        // JGit 的 worktree API 需要通过 Process 执行
        try {
            boolean branchExists = branchExists(mirrorPath, branchName);
            String output = runWorktreeAdd(mirrorPath, branchName, worktreePath, startBranch, branchExists);
            if (!branchExists && shouldRetryWithExistingBranch(output) && branchExists(mirrorPath, branchName)) {
                log.warn("检测到分支已被其他流程并发创建，改为复用已存在分支: branch={}", branchName);
                runWorktreeAdd(mirrorPath, branchName, worktreePath, startBranch, true);
            }
            log.info("工作树创建成功: branch={}, worktree={}", branchName, worktreePath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("执行 git worktree add 失败", e);
        } catch (IOException e) {
            throw new IllegalStateException("执行 git worktree add 失败", e);
        }
    }

    private String runWorktreeAdd(String mirrorPath, String branchName, String worktreePath,
                                  String startBranch, boolean branchExists) throws IOException, InterruptedException {
        ProcessBuilder pb = branchExists
                ? new ProcessBuilder("git", "worktree", "add", "--force", worktreePath, branchName)
                : new ProcessBuilder(
                        "git", "worktree", "add",
                        "-b", branchName,
                        worktreePath,
                        StringUtils.hasText(startBranch) ? startBranch : "HEAD"
                );
        pb.directory(new File(mirrorPath));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("git worktree add 超时: " + worktreePath);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            if (!branchExists && shouldRetryWithExistingBranch(output)) {
                return output;
            }
            throw new IllegalStateException("git worktree add 失败: " + output);
        }
        return output;
    }

    private boolean shouldRetryWithExistingBranch(String output) {
        return StringUtils.hasText(output) && output.contains("reference already exists");
    }

    /**
     * 移除工作树
     *
     * @param mirrorPath   裸仓库路径
     * @param worktreePath 工作树路径
     */
    public void removeWorktree(String mirrorPath, String worktreePath) throws GitAPIException {
        log.info("移除工作树: mirrorPath={}, worktree={}", mirrorPath, worktreePath);
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "worktree", "remove", "--force", worktreePath);
            pb.directory(new File(mirrorPath));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("git worktree remove 超时: worktree={}", worktreePath);
                return;
            }
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("git worktree remove 失败（非致命）: {}", output);
            }
            log.info("工作树移除成功: worktree={}", worktreePath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("执行 git worktree remove 被中断: {}", e.getMessage());
        } catch (IOException e) {
            log.warn("执行 git worktree remove 失败: {}", e.getMessage());
        }
    }

    /**
     * 创建功能分支
     *
     * @param mirrorPath  裸仓库路径
     * @param branchName  新分支名
     * @param startPoint 起始点（commit hash或分支名）
     */
    public void createBranch(String mirrorPath, String branchName, String startPoint) throws GitAPIException, IOException {
        log.info("创建功能分支: mirrorPath={}, branch={}, startPoint={}", mirrorPath, branchName, startPoint);
        try (Git mirror = openBareMirror(mirrorPath)) {
            var cmd = mirror.branchCreate().setName(branchName);
            if (StringUtils.hasText(startPoint)) {
                cmd.setStartPoint(startPoint);
            }
            cmd.call();
            log.info("功能分支创建成功: branch={}", branchName);
        }
    }

    /**
     * 列出所有分支
     *
     * @param worktreePath 工作树路径
     * @return 分支列表
     */
    public List<BranchInfo> listBranches(String worktreePath) throws IOException {
        log.info("列出分支: worktreePath={}", worktreePath);
        try (Git git = Git.open(new File(worktreePath))) {
            Map<String, BranchInfo> branches = new java.util.LinkedHashMap<>();
            String currentBranch = git.getRepository().getBranch();
            for (Ref ref : git.branchList().call()) {
                String name = ref.getName();
                String shortName = name.startsWith("refs/heads/") ? name.substring("refs/heads/".length()) : name;
                boolean isCurrent = shortName.equals(currentBranch);
                branches.put(shortName, new BranchInfo(shortName, isCurrent, ref.getObjectId().getName()));
            }

            for (String line : runGitCommandLines(worktreePath, "git", "for-each-ref", "--format=%(refname:short)|%(objectname)", "refs/heads")) {
                if (!StringUtils.hasText(line) || !line.contains("|")) {
                    continue;
                }
                String[] parts = line.split("\\|", 2);
                String shortName = parts[0].trim();
                if (!StringUtils.hasText(shortName)) {
                    continue;
                }
                String commitId = parts.length > 1 ? parts[1].trim() : "";
                BranchInfo existing = branches.get(shortName);
                branches.put(shortName, new BranchInfo(
                        shortName,
                        existing != null ? existing.isCurrent() : shortName.equals(currentBranch),
                        StringUtils.hasText(commitId) ? commitId : (existing == null ? "" : existing.commitId())
                ));
            }

            for (String line : runGitCommandLines(worktreePath, "git", "worktree", "list", "--porcelain")) {
                if (!line.startsWith("branch ")) {
                    continue;
                }
                String refName = line.substring("branch ".length()).trim();
                String shortName = refName.startsWith("refs/heads/") ? refName.substring("refs/heads/".length()) : refName;
                if (!StringUtils.hasText(shortName)) {
                    continue;
                }
                BranchInfo existing = branches.get(shortName);
                branches.put(shortName, new BranchInfo(
                        shortName,
                        existing != null ? existing.isCurrent() : shortName.equals(currentBranch),
                        existing == null ? "" : existing.commitId()
                ));
            }
            log.info("分支列表获取成功: count={}", branches.size());
            return new ArrayList<>(branches.values());
        } catch (GitAPIException e) {
            log.error("列出分支失败: worktreePath={}", worktreePath, e);
            throw new IOException("列出分支失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取当前分支名
     *
     * @param worktreePath 工作树路径
     * @return 当前分支名
     */
    public String getCurrentBranch(String worktreePath) throws IOException {
        try (Git git = Git.open(new File(worktreePath))) {
            return git.getRepository().getBranch();
        } catch (Exception ex) {
            String output = runGitCommand(worktreePath, "git", "branch", "--show-current");
            return output == null ? null : output.trim();
        }
    }

    /**
     * 获取当前 HEAD 提交 ID
     *
     * @param worktreePath 工作树路径
     * @return 当前提交 ID
     */
    public String getHeadCommitId(String worktreePath) throws IOException {
        String output = runGitCommand(worktreePath, "git", "rev-parse", "HEAD");
        return output == null ? null : output.trim();
    }

    /**
     * 比较两个分支差异
     */
    public WorkspaceBranchDiffVO compareBranches(String worktreePath, String sourceBranch, String targetBranch) throws IOException {
        return compareBranches(worktreePath, sourceBranch, targetBranch, null, null);
    }

    /**
     * 比较两个分支差异，优先基于真实工作树目录对比文件内容
     */
    public WorkspaceBranchDiffVO compareBranches(String worktreePath,
                                                 String sourceBranch,
                                                 String targetBranch,
                                                 String sourceWorktreePath,
                                                 String targetWorktreePath) throws IOException {
        if (!StringUtils.hasText(sourceBranch) || !StringUtils.hasText(targetBranch)) {
            throw new IOException("分支名称不能为空");
        }
        WorkspaceBranchDiffVO result = new WorkspaceBranchDiffVO();
        result.setSourceBranch(sourceBranch);
        result.setTargetBranch(targetBranch);

        String range = targetBranch + "..." + sourceBranch;
        try {
            String counts = runGitCommand(worktreePath, "git", "rev-list", "--left-right", "--count", range);
            String[] countArr = counts.trim().split("\\s+");
            if (countArr.length >= 2) {
                result.setBehindCount(parseInteger(countArr[0]));
                result.setAheadCount(parseInteger(countArr[1]));
            } else {
                result.setBehindCount(0);
                result.setAheadCount(0);
            }
        } catch (IOException ex) {
            log.warn("计算分支 ahead/behind 失败，已回退为 0: sourceBranch={}, targetBranch={}", sourceBranch, targetBranch, ex);
            result.setBehindCount(0);
            result.setAheadCount(0);
        }

        populateGitRefDiff(result, worktreePath, range);
        if ((result.getChangedFileCount() == null || result.getChangedFileCount() == 0)
                && populateWorktreeDiff(result, sourceWorktreePath, targetWorktreePath)) {
            return result;
        }
        return result;
    }

    private boolean populateWorktreeDiff(WorkspaceBranchDiffVO result, String sourceWorktreePath, String targetWorktreePath) throws IOException {
        if (!StringUtils.hasText(sourceWorktreePath) || !StringUtils.hasText(targetWorktreePath)) {
            return false;
        }
        Path sourcePath = Path.of(sourceWorktreePath);
        Path targetPath = Path.of(targetWorktreePath);
        if (!Files.isDirectory(sourcePath) || !Files.isDirectory(targetPath)) {
            return false;
        }

        List<BranchDiffFileVO> files = buildWorktreeDiffFiles(targetPath, sourcePath);
        int totalAdditions = files.stream().mapToInt(file -> file.getAdditions() == null ? 0 : file.getAdditions()).sum();
        int totalDeletions = files.stream().mapToInt(file -> file.getDeletions() == null ? 0 : file.getDeletions()).sum();
        result.setChangedFileCount(files.size());
        result.setAdditions(totalAdditions);
        result.setDeletions(totalDeletions);
        result.setFiles(files);
        return true;
    }

    private List<BranchDiffFileVO> buildWorktreeDiffFiles(Path targetPath, Path sourcePath) throws IOException {
        Map<String, Path> targetFiles = indexComparableFiles(targetPath);
        Map<String, Path> sourceFiles = indexComparableFiles(sourcePath);
        Map<String, BranchDiffFileVO> fileMap = new LinkedHashMap<>();
        for (String relativePath : targetFiles.keySet()) {
            fileMap.put(relativePath, null);
        }
        for (String relativePath : sourceFiles.keySet()) {
            fileMap.put(relativePath, null);
        }

        List<BranchDiffFileVO> files = new ArrayList<>();
        for (String relativePath : fileMap.keySet().stream().sorted().toList()) {
            Path oldFile = targetFiles.get(relativePath);
            Path newFile = sourceFiles.get(relativePath);
            if (oldFile != null && newFile != null && Files.mismatch(oldFile, newFile) == -1L) {
                continue;
            }
            BranchDiffFileVO file = new BranchDiffFileVO();
            file.setFilePath(relativePath);
            file.setChangeType(resolveChangeType(oldFile, newFile));
            int[] stats = readFileDiffStats(oldFile, newFile);
            file.setAdditions(stats[0]);
            file.setDeletions(stats[1]);
            file.setPatch(buildFilePatch(relativePath, oldFile, newFile));
            files.add(file);
        }
        return files;
    }

    private Map<String, Path> indexComparableFiles(Path rootPath) throws IOException {
        if (!Files.isDirectory(rootPath)) {
            return Map.of();
        }
        Map<String, Path> fileMap = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(rootPath)) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> {
                        Path relativePath = rootPath.relativize(path);
                        if (shouldIgnoreDiffPath(relativePath)) {
                            return;
                        }
                        fileMap.put(relativePath.toString().replace(File.separatorChar, '/'), path);
                    });
        }
        return fileMap;
    }

    private boolean shouldIgnoreDiffPath(Path relativePath) {
        if (relativePath == null || relativePath.getNameCount() == 0) {
            return true;
        }
        return DIFF_EXCLUDED_ROOTS.contains(relativePath.getName(0).toString());
    }

    private String resolveChangeType(Path oldFile, Path newFile) {
        if (oldFile == null) {
            return "A";
        }
        if (newFile == null) {
            return "D";
        }
        return "M";
    }

    private int[] readFileDiffStats(Path oldFile, Path newFile) throws IOException {
        String oldPath = oldFile == null ? "/dev/null" : oldFile.toString();
        String newPath = newFile == null ? "/dev/null" : newFile.toString();
        String output = runCommand(null, Set.of(0, 1), "git", "diff", "--no-index", "--numstat", oldPath, newPath);
        String line = output.lines().filter(StringUtils::hasText).findFirst().orElse("");
        if (!StringUtils.hasText(line)) {
            return new int[]{0, 0};
        }
        String[] parts = line.split("\\t", 3);
        int additions = parts.length > 0 ? parseInteger(parts[0]) : 0;
        int deletions = parts.length > 1 ? parseInteger(parts[1]) : 0;
        return new int[]{additions, deletions};
    }

    private String buildFilePatch(String relativePath, Path oldFile, Path newFile) throws IOException {
        String oldPath = oldFile == null ? "/dev/null" : oldFile.toString();
        String newPath = newFile == null ? "/dev/null" : newFile.toString();
        return runCommand(
                null,
                Set.of(0, 1),
                "diff",
                "-u",
                "-N",
                "--label",
                "a/" + relativePath,
                oldPath,
                "--label",
                "b/" + relativePath,
                newPath
        );
    }

    private void populateGitRefDiff(WorkspaceBranchDiffVO result, String worktreePath, String range) throws IOException {
        Map<String, int[]> statsMap = new HashMap<>();
        for (String line : runGitCommandLines(worktreePath, "git", "diff", "--numstat", range)) {
            String[] parts = line.split("\t");
            if (parts.length < 3) {
                continue;
            }
            String path = parts[2];
            statsMap.put(path, new int[]{parseInteger(parts[0]), parseInteger(parts[1])});
        }

        List<BranchDiffFileVO> files = new ArrayList<>();
        int totalAdditions = 0;
        int totalDeletions = 0;
        for (String line : runGitCommandLines(worktreePath, "git", "diff", "--name-status", range)) {
            String[] parts = line.split("\t");
            if (parts.length < 2) {
                continue;
            }
            String changeType = parts[0];
            String filePath = parts[parts.length - 1];
            int[] stats = statsMap.getOrDefault(filePath, new int[]{0, 0});
            BranchDiffFileVO file = new BranchDiffFileVO();
            file.setChangeType(changeType);
            file.setFilePath(filePath);
            file.setAdditions(stats[0]);
            file.setDeletions(stats[1]);
            file.setPatch(runGitCommand(worktreePath, "git", "diff", "--unified=3", range, "--", filePath));
            files.add(file);
            totalAdditions += stats[0];
            totalDeletions += stats[1];
        }

        result.setChangedFileCount(files.size());
        result.setAdditions(totalAdditions);
        result.setDeletions(totalDeletions);
        result.setFiles(files);
    }

    /**
     * 分支信息
     */
    public record BranchInfo(String name, boolean isCurrent, String commitId) {}

    /**
     * 提交并推送变更
     *
     * @param worktreePath 工作树路径
     * @param branchName   分支名
     * @param message      提交信息
     * @param credential   推送凭证
     */
    public void commitAndPush(String worktreePath, String branchName, String message, Map<String, Object> credential) throws GitAPIException, IOException {
        log.info("提交并推送: worktree={}, branch={}", worktreePath, branchName);
        try (Git git = Git.open(new File(worktreePath))) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage(message).call();

            RefSpec refSpec = new RefSpec("refs/heads/" + branchName + ":refs/heads/" + branchName);
            var pushCommand = git.push().setRefSpecs(refSpec);
            CredentialsProvider credProvider = buildCredentialsProvider(credential);
            if (credProvider != null) {
                pushCommand.setCredentialsProvider(credProvider);
            }
            pushCommand.call();
            log.info("提交并推送成功: branch={}", branchName);
        }
    }

    public boolean commitChanges(String worktreePath, String message) throws GitAPIException, IOException {
        return commitChanges(worktreePath, message, List.of());
    }

    public boolean commitChanges(String worktreePath, String message, List<String> forceIncludePaths) throws GitAPIException, IOException {
        Path gitWorktreeRoot = requireGitWorktreeRoot(worktreePath);
        if (gitWorktreeRoot == null) {
            log.info("目标路径不是 Git 工作树根目录，跳过提交: worktree={}", worktreePath);
            return false;
        }
        String normalizedWorktreePath = gitWorktreeRoot.toString();
        List<String> normalizedForceIncludePaths = normalizeForceIncludePaths(normalizedWorktreePath, forceIncludePaths);
        log.info("提交工作树变更: worktree={}, forceIncludePaths={}", normalizedWorktreePath, normalizedForceIncludePaths);
        if (!normalizedForceIncludePaths.isEmpty()) {
            return commitChangesWithCli(normalizedWorktreePath, message, normalizedForceIncludePaths);
        }
        try {
            try (Git git = Git.open(gitWorktreeRoot.toFile())) {
                if (git.status().call().isClean()) {
                    log.info("工作树无变更，跳过提交: worktree={}", normalizedWorktreePath);
                    return false;
                }
                git.add().addFilepattern(".").call();
                git.commit()
                        .setMessage(message)
                        .setAuthor("SchemaPlexAI", "noreply@schemaplexai.local")
                        .setCommitter("SchemaPlexAI", "noreply@schemaplexai.local")
                        .call();
                log.info("工作树提交成功: worktree={}", normalizedWorktreePath);
                return true;
            }
        } catch (Exception ex) {
            log.warn("JGit 提交工作树失败，回退到 git CLI: worktree={}, error={}", normalizedWorktreePath, ex.getMessage());
            return commitChangesWithCli(normalizedWorktreePath, message, List.of());
        }
    }

    private boolean commitChangesWithCli(String worktreePath, String message) throws IOException {
        return commitChangesWithCli(worktreePath, message, List.of());
    }

    private boolean commitChangesWithCli(String worktreePath, String message, List<String> forceIncludePaths) throws IOException {
        if (forceIncludePaths.isEmpty()) {
            String statusOutput = runGitCommand(worktreePath, "git", "status", "--porcelain");
            if (!StringUtils.hasText(statusOutput)) {
                log.info("工作树无变更，跳过提交: worktree={}", worktreePath);
                return false;
            }
            runGitCommand(worktreePath, "git", "add", "-A");
        } else {
            List<String> addCommand = new ArrayList<>(List.of("git", "add", "-f", "--"));
            addCommand.addAll(forceIncludePaths);
            runGitCommand(worktreePath, addCommand.toArray(new String[0]));
        }
        String stagedOutput = runGitCommand(worktreePath, "git", "diff", "--cached", "--name-only");
        if (!StringUtils.hasText(stagedOutput)) {
            log.info("工作树无可提交变更，跳过提交: worktree={}", worktreePath);
            return false;
        }
        runGitCommand(
                worktreePath,
                "git",
                "-c",
                "user.name=SchemaPlexAI",
                "-c",
                "user.email=noreply@schemaplexai.local",
                "commit",
                "-m",
                message
        );
        log.info("通过 git CLI 提交工作树成功: worktree={}", worktreePath);
        return true;
    }

    private List<String> normalizeForceIncludePaths(String worktreePath, List<String> forceIncludePaths) {
        if (!StringUtils.hasText(worktreePath) || forceIncludePaths == null || forceIncludePaths.isEmpty()) {
            return List.of();
        }
        Path worktreeRoot = Path.of(worktreePath).toAbsolutePath().normalize();
        List<String> normalizedPaths = new ArrayList<>();
        for (String forceIncludePath : forceIncludePaths) {
            if (!StringUtils.hasText(forceIncludePath)) {
                continue;
            }
            String relativePath = forceIncludePath.trim().replace('\\', '/');
            Path absolutePath = worktreeRoot.resolve(relativePath).normalize();
            if (!absolutePath.startsWith(worktreeRoot)) {
                log.warn("跳过越界的强制提交路径: worktree={}, path={}", worktreePath, forceIncludePath);
                continue;
            }
            if (!Files.exists(absolutePath)) {
                log.warn("跳过不存在的强制提交路径: worktree={}, path={}", worktreePath, forceIncludePath);
                continue;
            }
            if (!normalizedPaths.contains(relativePath)) {
                normalizedPaths.add(relativePath);
            }
        }
        return normalizedPaths;
    }

    /**
     * 检查工作树当前变更，区分“任意改动”与“实现类改动”
     */
    public WorkspaceChangeSummary inspectWorkspaceChanges(String worktreePath) throws IOException {
        Path gitWorktreeRoot = requireGitWorktreeRoot(worktreePath);
        if (gitWorktreeRoot == null) {
            log.info("目标路径不是 Git 工作树根目录，跳过变更检查: worktree={}", worktreePath);
            return new WorkspaceChangeSummary(true, List.of(), List.of());
        }
        String statusOutput = runGitCommand(gitWorktreeRoot.toString(), "git", "status", "--porcelain", "--untracked-files=all");
        if (!StringUtils.hasText(statusOutput)) {
            return new WorkspaceChangeSummary(true, List.of(), List.of());
        }

        List<String> changedFiles = new ArrayList<>();
        List<String> implementationFiles = new ArrayList<>();
        for (String line : statusOutput.lines().toList()) {
            if (!StringUtils.hasText(line)) {
                continue;
            }
            String filePath = extractStatusFilePath(line);
            if (!StringUtils.hasText(filePath)) {
                continue;
            }
            changedFiles.add(filePath);
            if (isImplementationEvidencePath(filePath)) {
                implementationFiles.add(filePath);
            }
        }
        return new WorkspaceChangeSummary(changedFiles.isEmpty(), List.copyOf(changedFiles), List.copyOf(implementationFiles));
    }

    /**
     * 确保裸仓库存在（若不存在则创建）
     */
    public void ensureBareMirror(String workspaceRoot, String mirrorPath) throws GitAPIException, IOException {
        Path path = Path.of(mirrorPath);
        if (Files.exists(path) && path.toFile().listFiles() != null && path.toFile().listFiles().length > 0) {
            log.debug("裸仓库已存在: mirrorPath={}", mirrorPath);
            return;
        }
        createBareMirror(workspaceRoot, mirrorPath);
    }

    /**
     * 生成本地存储路径
     */
    public String generateLocalPath(String tenantId, String workspaceId) {
        return workspaceRootPath + "/" + tenantId + "/" + workspaceId;
    }

    /**
     * 根据凭证信息构建 JGit CredentialsProvider
     */
    private CredentialsProvider buildCredentialsProvider(Map<String, Object> credential) {
        if (credential == null || credential.isEmpty()) {
            return null;
        }
        String type = String.valueOf(credential.getOrDefault("type", "token"));
        if ("token".equals(type)) {
            String token = String.valueOf(credential.getOrDefault("token", ""));
            // Git 平台通常用 token 作为密码，用户名随意
            return new UsernamePasswordCredentialsProvider("oauth2", token);
        } else if ("password".equals(type)) {
            String username = String.valueOf(credential.getOrDefault("username", ""));
            String password = String.valueOf(credential.getOrDefault("password", ""));
            return new UsernamePasswordCredentialsProvider(username, password);
        }
        return null;
    }

    private boolean branchExists(String mirrorPath, String branchName) throws IOException, GitAPIException {
        try (Git mirror = openBareMirror(mirrorPath)) {
            return mirror.branchList().call().stream()
                    .map(Ref::getName)
                    .map(name -> name.startsWith("refs/heads/") ? name.substring("refs/heads/".length()) : name)
                    .anyMatch(branchName::equals);
        }
    }

    private String extractStatusFilePath(String statusLine) {
        if (!StringUtils.hasText(statusLine)) {
            return null;
        }
        String normalized = statusLine.length() > 3 ? statusLine.substring(3).trim() : statusLine.trim();
        int renamedMarker = normalized.indexOf(" -> ");
        if (renamedMarker >= 0) {
            normalized = normalized.substring(renamedMarker + 4).trim();
        }
        return normalized.replace('\\', '/');
    }

    private boolean isImplementationEvidencePath(String filePath) {
        if (!StringUtils.hasText(filePath)) {
            return false;
        }
        String normalized = filePath.trim().replace('\\', '/');
        String lowerCasePath = normalized.toLowerCase(Locale.ROOT);
        if (lowerCasePath.startsWith("docs/")) {
            return false;
        }
        if (lowerCasePath.endsWith(".md")
                || lowerCasePath.endsWith(".txt")
                || lowerCasePath.endsWith(".png")
                || lowerCasePath.endsWith(".jpg")
                || lowerCasePath.endsWith(".jpeg")
                || lowerCasePath.endsWith(".gif")
                || lowerCasePath.endsWith(".svg")
                || lowerCasePath.endsWith(".pdf")) {
            return false;
        }
        return lowerCasePath.endsWith(".java")
                || lowerCasePath.endsWith(".kt")
                || lowerCasePath.endsWith(".groovy")
                || lowerCasePath.endsWith(".xml")
                || lowerCasePath.endsWith(".sql")
                || lowerCasePath.endsWith(".yml")
                || lowerCasePath.endsWith(".yaml")
                || lowerCasePath.endsWith(".json")
                || lowerCasePath.endsWith(".properties")
                || lowerCasePath.endsWith(".js")
                || lowerCasePath.endsWith(".jsx")
                || lowerCasePath.endsWith(".ts")
                || lowerCasePath.endsWith(".tsx")
                || lowerCasePath.endsWith(".vue")
                || lowerCasePath.endsWith(".py")
                || lowerCasePath.endsWith(".rb")
                || lowerCasePath.endsWith(".go")
                || lowerCasePath.endsWith(".rs")
                || lowerCasePath.endsWith(".c")
                || lowerCasePath.endsWith(".cc")
                || lowerCasePath.endsWith(".cpp")
                || lowerCasePath.endsWith(".h")
                || lowerCasePath.endsWith(".hpp")
                || lowerCasePath.endsWith(".cs")
                || lowerCasePath.endsWith(".sh")
                || lowerCasePath.endsWith(".gradle")
                || lowerCasePath.endsWith("pom.xml")
                || lowerCasePath.endsWith("build.gradle")
                || lowerCasePath.endsWith("build.gradle.kts")
                || lowerCasePath.endsWith("settings.gradle")
                || lowerCasePath.endsWith("settings.gradle.kts")
                || lowerCasePath.endsWith("package.json")
                || lowerCasePath.endsWith("package-lock.json")
                || lowerCasePath.endsWith("pnpm-lock.yaml")
                || lowerCasePath.endsWith("dockerfile");
    }

    /**
     * 执行 git 命令并返回输出内容
     */
    private String runGitCommand(String worktreePath, String... command) throws IOException {
        return runCommand(worktreePath, Set.of(0), command);
    }

    private List<String> runGitCommandLines(String worktreePath, String... command) throws IOException {
        String output = runGitCommand(worktreePath, command);
        return output.lines()
                .filter(StringUtils::hasText)
                .toList();
    }

    private String runCommand(String workingDirectory, Set<Integer> allowedExitCodes, String... command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (StringUtils.hasText(workingDirectory)) {
            builder.directory(new File(workingDirectory));
        }
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
        Thread readerThread = new Thread(() -> {
            try (InputStream inputStream = process.getInputStream()) {
                inputStream.transferTo(outputBuffer);
            } catch (IOException ignored) {
                // 命令失败时由主线程统一处理输出与异常。
            }
        }, "git-command-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        try {
            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                joinReaderThread(readerThread);
                throw new IOException("执行命令超时: " + String.join(" ", command));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            joinReaderThread(readerThread);
            throw new IOException("执行命令被中断", e);
        }
        joinReaderThread(readerThread);
        String output = outputBuffer.toString(StandardCharsets.UTF_8);
        int exitCode = process.exitValue();
        if (!allowedExitCodes.contains(exitCode)) {
            throw new IOException("执行命令失败: " + String.join(" ", command) + System.lineSeparator() + output);
        }
        return output;
    }

    private Path requireGitWorktreeRoot(String worktreePath) throws IOException {
        if (!StringUtils.hasText(worktreePath)) {
            return null;
        }
        Path rootPath = Path.of(worktreePath).toAbsolutePath().normalize();
        if (!Files.isDirectory(rootPath)) {
            return null;
        }
        Path gitMetadata = rootPath.resolve(".git");
        if (!Files.exists(gitMetadata)) {
            return null;
        }
        return rootPath;
    }

    private void joinReaderThread(Thread readerThread) throws IOException {
        try {
            readerThread.join(TimeUnit.SECONDS.toMillis(1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待命令输出线程结束时被中断", e);
        }
    }

    private int parseInteger(String value) {
        if (!StringUtils.hasText(value) || "-".equals(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    public record WorkspaceChangeSummary(
            boolean clean,
            List<String> changedFiles,
            List<String> implementationFiles
    ) {

        public int totalChangedFiles() {
            return changedFiles == null ? 0 : changedFiles.size();
        }

        public int implementationChangedFileCount() {
            return implementationFiles == null ? 0 : implementationFiles.size();
        }
    }
}
