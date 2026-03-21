package com.schemaplexai.service.integration.cicd.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.service.integration.cicd.CicdTrigger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * GitLab CI Pipeline 触发器
 * 通过 GitLab Pipeline Trigger API 触发构建
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitlabCiTrigger implements CicdTrigger {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public String getPipelineType() {
        return "gitlab_ci";
    }

    @Override
    public Map<String, Object> trigger(Map<String, Object> config) throws Exception {
        String gitlabUrl = String.valueOf(config.getOrDefault("gitlab_url", "https://gitlab.com"));
        String projectId = String.valueOf(config.getOrDefault("project_id", ""));
        String triggerToken = String.valueOf(config.getOrDefault("trigger_token", ""));
        String ref = String.valueOf(config.getOrDefault("ref", "main"));

        if (projectId.isBlank() || triggerToken.isBlank()) {
            throw new IllegalArgumentException("GitLab CI 配置缺少 project_id 或 trigger_token");
        }

        // GitLab Pipeline Trigger API: POST /api/v4/projects/:id/trigger/pipeline
        String triggerUrl = gitlabUrl.replaceAll("/$", "") + "/api/v4/projects/" + projectId + "/trigger/pipeline";

        FormBody formBody = new FormBody.Builder()
                .add("token", triggerToken)
                .add("ref", ref)
                .build();

        Request request = new Request.Builder()
                .url(triggerUrl)
                .post(formBody)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            Map<String, Object> result = new HashMap<>();
            result.put("status_code", response.code());
            if (response.isSuccessful() && response.body() != null) {
                Map<String, Object> respData = objectMapper.readValue(response.body().string(), new TypeReference<>() {});
                result.put("success", true);
                result.put("pipeline_id", respData.get("id"));
                result.put("web_url", respData.get("web_url"));
                result.put("status", respData.get("status"));
                log.info("GitLab CI 构建触发成功: projectId={}, pipelineId={}", projectId, respData.get("id"));
            } else {
                result.put("success", false);
                result.put("error", "触发失败: HTTP " + response.code());
                log.warn("GitLab CI 构建触发失败: projectId={}, code={}", projectId, response.code());
            }
            return result;
        }
    }
}
