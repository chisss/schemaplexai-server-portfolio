package com.schemaplexai.service.integration.platform.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.enums.GitPlatformEnum;
import com.schemaplexai.common.enums.MergeRequestStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.service.integration.platform.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GitLab 平台适配器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitLabAdapter extends AbstractGitPlatformAdapter {

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    private static final String TOKEN_HEADER = "PRIVATE-TOKEN";

    @Override
    public String platform() {
        return GitPlatformEnum.GITLAB.getCode();
    }

    @Override
    public ConnectionTestResult testConnection(Map<String, Object> config) {
        String apiUrl = resolveApiUrl(config, GitPlatformEnum.GITLAB);
        String token = requireToken(config);
        long start = System.currentTimeMillis();

        Request request = new Request.Builder()
                .url(apiUrl + "/api/v4/user")
                .header(TOKEN_HEADER, token)
                .get().build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            long latency = System.currentTimeMillis() - start;
            if (!response.isSuccessful()) {
                return ConnectionTestResult.builder()
                        .connected(false).latencyMs(latency)
                        .errorMessage("HTTP " + response.code() + ": " + response.message())
                        .build();
            }
            JsonNode body = objectMapper.readTree(response.body().string());
            return ConnectionTestResult.builder()
                    .connected(true).latencyMs(latency)
                    .username(body.path("username").asText())
                    .scopes(List.of()).build();
        } catch (IOException e) {
            return ConnectionTestResult.builder()
                    .connected(false)
                    .latencyMs(System.currentTimeMillis() - start)
                    .errorMessage("连接失败: " + e.getMessage()).build();
        }
    }

    @Override
    public List<RemoteProject> syncProjects(Map<String, Object> config) {
        String apiUrl = resolveApiUrl(config, GitPlatformEnum.GITLAB);
        String token = requireToken(config);

        Request request = new Request.Builder()
                .url(apiUrl + "/api/v4/projects?membership=true&per_page=100&order_by=updated_at")
                .header(TOKEN_HEADER, token)
                .get().build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("GitLab 项目同步失败: HTTP {}", response.code());
                return List.of();
            }
            JsonNode projects = objectMapper.readTree(response.body().string());
            List<RemoteProject> result = new ArrayList<>();
            for (JsonNode project : projects) {
                result.add(RemoteProject.builder()
                        .remoteId(project.path("id").asText())
                        .fullName(project.path("path_with_namespace").asText())
                        .name(project.path("name").asText())
                        .description(project.path("description").asText(""))
                        .defaultBranch(project.path("default_branch").asText("main"))
                        .httpUrl(project.path("http_url_to_repo").asText())
                        .sshUrl(project.path("ssh_url_to_repo").asText())
                        .build());
            }
            return result;
        } catch (IOException e) {
            log.error("GitLab 项目同步异常", e);
            return List.of();
        }
    }

    @Override
    public List<RemoteRepositoryTreeNode> getRepositoryTree(Map<String, Object> config,
                                                            String projectIdentifier,
                                                            String ref,
                                                            String path) {
        String apiUrl = resolveApiUrl(config, GitPlatformEnum.GITLAB);
        String token = requireToken(config);

        HttpUrl baseUrl = HttpUrl.parse(apiUrl + "/api/v4/projects/" + projectIdentifier + "/repository/tree");
        if (baseUrl == null) {
            throw new BusinessException(ResultCode.INTEGRATION_CONFIG_INVALID, "GitLab API 地址无效");
        }

        HttpUrl.Builder urlBuilder = baseUrl.newBuilder()
                .addQueryParameter("per_page", "100");
        if (ref != null && !ref.isBlank()) {
            urlBuilder.addQueryParameter("ref", ref.trim());
        }
        if (path != null && !path.isBlank() && !"/".equals(path.trim())) {
            urlBuilder.addQueryParameter("path", path.trim());
        }

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .header(TOKEN_HEADER, token)
                .get().build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("GitLab 文件树读取失败: projectId={}, code={}", projectIdentifier, response.code());
                throw new BusinessException(ResultCode.INTEGRATION_SYNC_FAILED,
                        "读取 GitLab 文件树失败: HTTP " + response.code());
            }
            JsonNode treeNodes = objectMapper.readTree(response.body().string());
            List<RemoteRepositoryTreeNode> result = new ArrayList<>();
            for (JsonNode treeNode : treeNodes) {
                String nodeType = "tree".equals(treeNode.path("type").asText()) ? "directory" : "file";
                result.add(RemoteRepositoryTreeNode.builder()
                        .path(treeNode.path("path").asText())
                        .name(treeNode.path("name").asText())
                        .type(nodeType)
                        .leaf("file".equals(nodeType))
                        .build());
            }
            return result;
        } catch (IOException e) {
            log.error("GitLab 文件树读取异常: projectId={}", projectIdentifier, e);
            throw new BusinessException(ResultCode.INTEGRATION_SYNC_FAILED,
                    "读取 GitLab 文件树失败: " + e.getMessage());
        }
    }

    @Override
    public RemoteMergeRequest createMergeRequest(Map<String, Object> config,
                                                  String projectIdentifier,
                                                  String sourceBranch, String targetBranch,
                                                  String title, String description) {
        String apiUrl = resolveApiUrl(config, GitPlatformEnum.GITLAB);
        String token = requireToken(config);

        Map<String, Object> payload = Map.of(
                "source_branch", sourceBranch,
                "target_branch", targetBranch,
                "title", title,
                "description", description != null ? description : "");

        try {
            RequestBody body = RequestBody.create(
                    objectMapper.writeValueAsBytes(payload),
                    MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(apiUrl + "/api/v4/projects/" + projectIdentifier + "/merge_requests")
                    .header(TOKEN_HEADER, token)
                    .post(body).build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                JsonNode mr = objectMapper.readTree(response.body().string());
                return RemoteMergeRequest.builder()
                        .remoteMrId(mr.path("iid").asText())
                        .webUrl(mr.path("web_url").asText())
                        .status(MergeRequestStatusEnum.OPENED.getCode())
                        .sourceBranch(sourceBranch)
                        .targetBranch(targetBranch).build();
            }
        } catch (Exception e) {
            throw new BusinessException(ResultCode.INTEGRATION_SYNC_FAILED,
                    "创建 MR 失败: " + e.getMessage());
        }
    }

    @Override
    public RemoteMergeRequest getMergeRequestStatus(Map<String, Object> config,
                                                     String projectIdentifier,
                                                     String remoteMrId) {
        String apiUrl = resolveApiUrl(config, GitPlatformEnum.GITLAB);
        String token = requireToken(config);

        Request request = new Request.Builder()
                .url(apiUrl + "/api/v4/projects/" + projectIdentifier + "/merge_requests/" + remoteMrId)
                .header(TOKEN_HEADER, token)
                .get().build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            JsonNode mr = objectMapper.readTree(response.body().string());
            String state = mr.path("state").asText();
            String status = "merged".equals(state)
                    ? MergeRequestStatusEnum.MERGED.getCode()
                    : MergeRequestStatusEnum.fromCode(state).getCode();
            return RemoteMergeRequest.builder()
                    .remoteMrId(remoteMrId)
                    .webUrl(mr.path("web_url").asText())
                    .status(status)
                    .sourceBranch(mr.path("source_branch").asText())
                    .targetBranch(mr.path("target_branch").asText()).build();
        } catch (Exception e) {
            throw new BusinessException(ResultCode.INTEGRATION_SYNC_FAILED,
                    "查询 MR 状态失败: " + e.getMessage());
        }
    }
}
