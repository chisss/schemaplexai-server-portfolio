package com.schemaplexai.service.integration.git;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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
        File dir = new File(localPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

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
