package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.integration.IntegrationCreateRequest;
import com.schemaplexai.model.dto.integration.IntegrationQueryRequest;
import com.schemaplexai.model.dto.integration.IntegrationUpdateRequest;
import com.schemaplexai.model.dto.integration.ProjectImportRequest;
import com.schemaplexai.model.vo.integration.ConnectionTestVO;
import com.schemaplexai.model.vo.integration.IntegrationProjectVO;
import com.schemaplexai.model.vo.integration.IntegrationRepositoryTreeNodeVO;
import com.schemaplexai.model.vo.integration.IntegrationRepositoryVO;
import com.schemaplexai.model.vo.integration.IntegrationVO;
import com.schemaplexai.model.vo.integration.WebhookEventVO;
import com.schemaplexai.service.integration.IntegrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 集成扩展控制器
 */
@RestController
@RequestMapping("/integrations")
@RequiredArgsConstructor
@Tag(name = "集成扩展")
public class IntegrationController {

    private final IntegrationService integrationService;

    @PostMapping
    @Operation(summary = "创建集成配置")
    public R<IntegrationVO> create(@Valid @RequestBody IntegrationCreateRequest request) {
        return R.ok(integrationService.create(request));
    }

    @GetMapping
    @Operation(summary = "分页查询集成配置")
    public R<PageResult<IntegrationVO>> page(IntegrationQueryRequest request) {
        return R.ok(integrationService.page(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取集成配置详情")
    public R<IntegrationVO> getById(@PathVariable String id) {
        return R.ok(integrationService.getById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新集成配置")
    public R<IntegrationVO> update(@PathVariable String id,
                                   @Valid @RequestBody IntegrationUpdateRequest request) {
        return R.ok(integrationService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除集成配置")
    public R<Void> delete(@PathVariable String id) {
        integrationService.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "测试集成连接")
    public R<ConnectionTestVO> testConnection(@PathVariable String id) {
        return R.ok(integrationService.testConnection(id));
    }

    @PostMapping("/{id}/sync")
    @Operation(summary = "触发集成同步")
    public R<Map<String, Object>> sync(@PathVariable String id) {
        return R.ok(integrationService.sync(id));
    }

    // ===== 项目管理 =====

    @GetMapping("/{id}/projects/available")
    @Operation(summary = "获取集成平台上可用的项目列表")
    public R<List<Map<String, Object>>> listAvailableProjects(@PathVariable String id) {
        return R.ok(integrationService.listAvailableProjects(id));
    }

    @PostMapping("/{id}/projects/import")
    @Operation(summary = "从集成平台导入项目")
    public R<IntegrationProjectVO> importProject(@PathVariable String id,
                                                  @Valid @RequestBody ProjectImportRequest request) {
        return R.ok(integrationService.importProject(id, request));
    }

    @GetMapping("/{id}/projects")
    @Operation(summary = "获取已导入的集成项目列表")
    public R<List<IntegrationProjectVO>> listImportedProjects(@PathVariable String id) {
        return R.ok(integrationService.listImportedProjects(id));
    }

    @GetMapping("/{id}/repos")
    @Operation(summary = "获取远端仓库列表")
    public R<List<IntegrationRepositoryVO>> listRepositories(@PathVariable String id) {
        return R.ok(integrationService.listRepositories(id));
    }

    @GetMapping("/{id}/repos/{repoId}/tree")
    @Operation(summary = "获取远端仓库文件树")
    public R<List<IntegrationRepositoryTreeNodeVO>> listRepositoryTree(@PathVariable String id,
                                                                       @PathVariable String repoId,
                                                                       @RequestParam(required = false) String ref,
                                                                       @RequestParam(required = false, defaultValue = "/") String path) {
        return R.ok(integrationService.listRepositoryTree(id, repoId, ref, path));
    }

    @GetMapping("/projects/all")
    @Operation(summary = "获取当前租户下所有已导入的集成项目（跨所有集成）")
    public R<List<IntegrationProjectVO>> listAllImportedProjects() {
        return R.ok(integrationService.listAllImportedProjects());
    }

    @GetMapping("/webhook-events")
    @Operation(summary = "分页查询Webhook事件")
    public R<PageResult<WebhookEventVO>> pageWebhookEvents(
            @RequestParam(required = false) String integrationId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return R.ok(integrationService.pageWebhookEvents(integrationId, eventType, status, page, size));
    }
}
