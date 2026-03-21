package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查接口
 */
@RestController
@Tag(name = "健康检查")
public class HealthController {

    @GetMapping("/health")
    @Operation(summary = "健康检查")
    public R<Map<String, String>> health() {
        return R.ok(Map.of("status", "UP"));
    }
}
