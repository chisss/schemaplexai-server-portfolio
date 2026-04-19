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
import java.util.*;

/**
 * GitHub 平台适配器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitHubAdapter extends AbstractGitPlatformAdapter {

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    private static final String ACCEPT_HEADER = "application/vnd.github+json";

    @Override
    public String platform() {
        return GitPlatformEnum.GITHUB.getCode();
    }

    @Override
    public ConnectionTestResult testConnection(Map<String, Object> config) {
        String apiUrl = resolveApiUrl(config, GitPlatformEnum.GITHUB);
        String token = requireToken(config);
        long start = System.currentTimeMillis();

        Request request = new Request.Builder()
                .url(apiUrl + "/user")
                .header("Authorization", "Bearer " + token)
                .header("Accept", ACCEPT_HEADER)
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
            String scopeHeader = response.header("X-OAuth-Scopes");
            List<String> scopes = scopeHeader != null
                    ? Arrays.asList(scopeHeader.split(",\\s*"))
                    : List.of();

            return ConnectionTestResult.builder()
                    .connected(true).latencyMs(latency)
                    .username(body.path("login").asText())
                    .scopes(scopes).build();
        } catch (IOException e) {
            return ConnectionTestResult.builder()
                    .connected(false)
                    .latencyMs(System.currentTimeMillis() - start)
                    .errorMessage("连接失败: " + e.getMessage()).build();
        }
    }

    @Override
    public List<RemoteProject> syncProjects(Map<String, Object> config) {
        String apiUrl = resolveApiUrl(config, GitPlatformEnum.GITHUB);
        String token = requireToken(config);

        Request request = new Request.Builder()
                .url(apiUrl + "/user/repos?per_page=100&sort=updated")
                .header("Authorization", "Bearer " + token)
                .header("Accept", ACCEPT_HEADER)
                .get().build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("GitHub 项目同步失败: HTTP {}", response.code());
                return List.of();
            }
            JsonNode repos = objectMapper.readTree(response.body().string());
            List<RemoteProject> projects = new ArrayList<>();
            for (JsonNode repo : repos) {
                projects.add(RemoteProject.builder()
                        .remoteId(repo.path("id").asText())
                        .fullName(repo.path("full_name").asText())
                        .name(repo.path("name").asText())
                        .description(repo.path("description").asText(""))
                        .defaultBranch(repo.path("default_branch").asText("main"))
                        .httpUrl(repo.path("clone_url").asText())
                        .sshUrl(repo.path("ssh_url").asText())
                        .build());
            }
            return projects;
        } catch (IOException e) {
            log.error("GitHub 项目同步异常", e);
            return List.of();
        }
    }

    @Override
    public RemoteMergeRequest createMergeRequest(Map<String, Object> config,
                                                  String projectIdentifier,
                                                  String sourceBranch, String targetBranch,
                                                  String title, String description) {
        String apiUrl = resolveApiUrl(config, GitPlatformEnum.GITHUB);
        String token = requireToken(config);

        Map<String, Object> payload = Map.of(
                "title", title,
                "body", description != null ? description : "",
                "head", sourceBranch,
                "base", targetBranch);

        try {
            RequestBody body = RequestBody.create(
                    objectMapper.writeValueAsBytes(payload),
                    MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(apiUrl + "/repos/" + projectIdentifier + "/pulls")
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", ACCEPT_HEADER)
                    .post(body).build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                JsonNode pr = objectMapper.readTree(response.body().string());
                return RemoteMergeRequest.builder()
                        .remoteMrId(pr.path("number").asText())
                        .webUrl(pr.path("html_url").asText())
                        .status(MergeRequestStatusEnum.OPENED.getCode())
                        .sourceBranch(sourceBranch)
                        .targetBranch(targetBranch).build();
            }
        } catch (Exception e) {
            throw new BusinessException(ResultCode.INTEGRATION_SYNC_FAILED,
                    "创建 PR 失败: " + e.getMessage());
        }
    }

    @Override
    public RemoteMergeRequest getMergeRequestStatus(Map<String, Object> config,
                                                     String projectIdentifier,
                                                     String remoteMrId) {
        String apiUrl = resolveApiUrl(config, GitPlatformEnum.GITHUB);
        String token = requireToken(config);

        Request request = new Request.Builder()
                .url(apiUrl + "/repos/" + projectIdentifier + "/pulls/" + remoteMrId)
                .header("Authorization", "Bearer " + token)
                .header("Accept", ACCEPT_HEADER)
                .get().build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            JsonNode pr = objectMapper.readTree(response.body().string());
            boolean merged = pr.path("merged").asBoolean(false);
            String state = merged
                    ? MergeRequestStatusEnum.MERGED.getCode()
                    : MergeRequestStatusEnum.fromCode(pr.path("state").asText()).getCode();
            return RemoteMergeRequest.builder()
                    .remoteMrId(remoteMrId)
                    .webUrl(pr.path("html_url").asText())
                    .status(state)
                    .sourceBranch(pr.path("head").path("ref").asText())
                    .targetBranch(pr.path("base").path("ref").asText()).build();
        } catch (Exception e) {
            throw new BusinessException(ResultCode.INTEGRATION_SYNC_FAILED,
                    "查询 PR 状态失败: " + e.getMessage());
        }
    }
}
