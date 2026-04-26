package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口
 */
@RestController
@Tag(name = "健康检查")
public class HealthController {

    private final Environment environment;

    @Value("${git.commit.id.abbrev:unknown}")
    private String gitCommit;

    @Value("${build.time:unknown}")
    private String buildTime;

    public HealthController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping("/health")
    @Operation(summary = "健康检查")
    public R<Map<String, String>> health() {
        return R.ok(Map.of("status", "UP"));
    }

    @GetMapping("/health/build-info")
    @Operation(summary = "运行构建信息")
    public R<Map<String, Object>> buildInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("status", "UP");
        info.put("startTime", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()).atZone(ZoneId.systemDefault())));
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("activeProfiles", Arrays.asList(environment.getActiveProfiles()));
        info.put("buildTime", buildTime);
        info.put("gitCommit", gitCommit);
        return R.ok(info);
    }
}
