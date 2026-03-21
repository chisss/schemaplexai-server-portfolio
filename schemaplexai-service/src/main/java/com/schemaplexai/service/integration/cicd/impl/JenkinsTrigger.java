package com.schemaplexai.service.integration.cicd.impl;

import com.schemaplexai.service.integration.cicd.CicdTrigger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Jenkins Pipeline 触发器
 * 通过 Jenkins Remote Build API 触发构建
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JenkinsTrigger implements CicdTrigger {

    private final OkHttpClient httpClient;

    @Override
    public String getPipelineType() {
        return "jenkins";
    }

    @Override
    public Map<String, Object> trigger(Map<String, Object> config) throws Exception {
        String baseUrl = String.valueOf(config.getOrDefault("jenkins_url", ""));
        String jobName = String.valueOf(config.getOrDefault("job_name", ""));
        String username = String.valueOf(config.getOrDefault("username", ""));
        String apiToken = String.valueOf(config.getOrDefault("api_token", ""));

        if (baseUrl.isBlank() || jobName.isBlank()) {
            throw new IllegalArgumentException("Jenkins 配置缺少 jenkins_url 或 job_name");
        }

        // Jenkins 远程构建 API: POST {baseUrl}/job/{jobName}/build
        String triggerUrl = baseUrl.replaceAll("/$", "") + "/job/" + jobName + "/build";

        Request.Builder requestBuilder = new Request.Builder()
                .url(triggerUrl)
                .post(RequestBody.create(new byte[0]));

        // Jenkins 使用 Basic Auth
        if (!username.isBlank() && !apiToken.isBlank()) {
            requestBuilder.header("Authorization", Credentials.basic(username, apiToken));
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            Map<String, Object> result = new HashMap<>();
            result.put("status_code", response.code());
            result.put("trigger_url", triggerUrl);
            // Jenkins 返回 201 表示成功触发
            if (response.code() == 201 || response.isSuccessful()) {
                result.put("success", true);
                // 从 Location header 获取队列 ID
                String location = response.header("Location");
                if (location != null) {
                    result.put("queue_url", location);
                }
                log.info("Jenkins 构建触发成功: job={}", jobName);
            } else {
                result.put("success", false);
                result.put("error", "触发失败: HTTP " + response.code());
                log.warn("Jenkins 构建触发失败: job={}, code={}", jobName, response.code());
            }
            return result;
        }
    }
}
