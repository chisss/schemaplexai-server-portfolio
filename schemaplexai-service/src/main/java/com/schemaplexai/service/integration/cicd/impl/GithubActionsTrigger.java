package com.schemaplexai.service.integration.cicd.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.service.integration.cicd.CicdTrigger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * GitHub Actions 触发器
 * 通过 GitHub API dispatch workflow 触发构建
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubActionsTrigger implements CicdTrigger {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public String getPipelineType() {
        return "github_actions";
    }

    @Override
    public Map<String, Object> trigger(Map<String, Object> config) throws Exception {
        String owner = String.valueOf(config.getOrDefault("owner", ""));
        String repo = String.valueOf(config.getOrDefault("repo", ""));
        String workflowId = String.valueOf(config.getOrDefault("workflow_id", ""));
        String ref = String.valueOf(config.getOrDefault("ref", "main"));
        String token = String.valueOf(config.getOrDefault("token", ""));

        if (owner.isBlank() || repo.isBlank() || workflowId.isBlank()) {
            throw new IllegalArgumentException("GitHub Actions 配置缺少 owner、repo 或 workflow_id");
        }

        // GitHub API: POST /repos/{owner}/{repo}/actions/workflows/{workflow_id}/dispatches
        String triggerUrl = "https://api.github.com/repos/" + owner + "/" + repo
                + "/actions/workflows/" + workflowId + "/dispatches";

        Map<String, Object> body = Map.of("ref", ref);
        String json = objectMapper.writeValueAsString(body);

        Request.Builder requestBuilder = new Request.Builder()
                .url(triggerUrl)
                .post(RequestBody.create(json, JSON_TYPE))
                .header("Accept", "application/vnd.github+json");

        if (!token.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            Map<String, Object> result = new HashMap<>();
            result.put("status_code", response.code());
            // GitHub 返回 204 表示成功
            if (response.code() == 204 || response.isSuccessful()) {
                result.put("success", true);
                result.put("message", "GitHub Actions workflow 已触发");
                log.info("GitHub Actions 构建触发成功: {}/{}, workflow={}", owner, repo, workflowId);
            } else {
                result.put("success", false);
                String errBody = response.body() != null ? response.body().string() : "";
                result.put("error", "触发失败: HTTP " + response.code() + " " + errBody);
                log.warn("GitHub Actions 构建触发失败: {}/{}, code={}", owner, repo, response.code());
            }
            return result;
        }
    }
}
