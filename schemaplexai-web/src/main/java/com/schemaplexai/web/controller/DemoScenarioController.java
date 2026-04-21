package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.service.demo.DemoScenarioDatasetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 演示场景公开数据接口
 */
@RestController
@RequestMapping("/demo/scenario-datasets")
@RequiredArgsConstructor
@Tag(name = "演示场景数据")
public class DemoScenarioController {

    private final DemoScenarioDatasetService demoScenarioDatasetService;

    @GetMapping
    @Operation(summary = "获取全部演示场景数据")
    public R<List<Map<String, Object>>> listAll() {
        return R.ok(demoScenarioDatasetService.listAll());
    }

    @GetMapping("/{sceneKey}")
    @Operation(summary = "获取单个演示场景数据")
    public R<Map<String, Object>> getBySceneKey(@PathVariable String sceneKey) {
        return R.ok(demoScenarioDatasetService.getBySceneKey(sceneKey));
    }
}
