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
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Git 操作服务
 * 封装 JGit 的 clone/pull/磁盘计算等操作
 */
@Slf4j
@Service
public class GitOperationService {

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
    public Git createBareMirror(String mirrorPath) throws GitAPIException, IOException {
        Path path = Path.of(mirrorPath);
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建裸仓库目录: " + mirrorPath, e);
        }

        File dir = path.toFile();
        if (dir.exists() && dir.listFiles() != null && dir.listFiles().length > 0) {
            log.info("裸仓库已存在，直接打开: mirrorPath={}", mirrorPath);
            return Git.open(dir);
        }

        Git git = Git.init()
                .setDirectory(dir)
                .setBare(true)
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
            ProcessBuilder pb = new ProcessBuilder(
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
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IllegalStateException("git worktree add 失败: " + output);
            }
            log.info("工作树创建成功: branch={}, worktree={}", branchName, worktreePath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("执行 git worktree add 失败", e);
        } catch (IOException e) {
            throw new IllegalStateException("执行 git worktree add 失败", e);
        }
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
            List<BranchInfo> branches = new java.util.ArrayList<>();
            for (Ref ref : git.branchList().call()) {
                String name = ref.getName();
                String shortName = name.startsWith("refs/heads/") ? name.substring("refs/heads/".length()) : name;
                boolean isCurrent = shortName.equals(git.getRepository().getBranch());
                branches.add(new BranchInfo(shortName, isCurrent, ref.getObjectId().getName()));
            }
            log.info("分支列表获取成功: count={}", branches.size());
            return branches;
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
        } catch (IOException e) {
            log.error("获取当前分支失败: worktreePath={}", worktreePath, e);
            throw e;
        }
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

    /**
     * 确保裸仓库存在（若不存在则创建）
     */
    public void ensureBareMirror(String workspaceRoot, String mirrorPath) throws GitAPIException, IOException {
        Path path = Path.of(mirrorPath);
        if (Files.exists(path) && path.toFile().listFiles() != null && path.toFile().listFiles().length > 0) {
            log.debug("裸仓库已存在: mirrorPath={}", mirrorPath);
            return;
        }
        createBareMirror(mirrorPath);
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
}
