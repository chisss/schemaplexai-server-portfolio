package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.system.AiModelCreateRequest;
import com.schemaplexai.model.dto.system.AiModelRouteCreateRequest;
import com.schemaplexai.model.dto.system.AiModelRouteUpdateRequest;
import com.schemaplexai.model.dto.system.AiModelUpdateRequest;
import com.schemaplexai.model.dto.system.RagConfigUpdateRequest;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.TeamTemplate;
import com.schemaplexai.model.vo.system.AiModelRouteVO;
import com.schemaplexai.model.vo.system.AiModelRealHealthCheckVO;
import com.schemaplexai.model.vo.system.ConnectivityTestResultVO;
import com.schemaplexai.model.vo.system.RagConfigVO;
import com.schemaplexai.model.vo.system.RagOperationLogVO;
import com.schemaplexai.model.vo.system.RouteAnalysisVO;
import com.schemaplexai.service.config.SystemConfigService;
import com.schemaplexai.service.ai.AiModelRealHealthCheckService;
import com.schemaplexai.service.rag.RagConfigService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统配置控制器 - AI模型配置
 * 用户/角色/菜单/租户管理已拆分到各自的Controller
 */
@RestController
@RequestMapping("/system")
@RequiredArgsConstructor
@Tag(name = "系统配置")
public class SystemController {

    private final SystemConfigService systemConfigService;
    private final RagConfigService ragConfigService;
    private final AiModelRealHealthCheckService aiModelRealHealthCheckService;

    // ==================== AI模型管理 ====================

    @GetMapping("/models")
    @Operation(summary = "获取AI模型列表（仅active）")
    public R<List<AiModel>> listAiModels() {
        return R.ok(systemConfigService.listAiModels());
    }

    @GetMapping("/models/{id}")
    @Operation(summary = "获取AI模型详情")
    public R<AiModel> getAiModelById(@PathVariable String id) {
        return R.ok(systemConfigService.getAiModelById(id));
    }

    @GetMapping("/models/all")
    @Operation(summary = "获取全部AI模型（含inactive）")
    public R<List<AiModel>> listAllAiModels() {
        return R.ok(systemConfigService.listAllAiModels());
    }

    @PostMapping("/models")
    @Operation(summary = "创建AI模型")
    public R<AiModel> createAiModel(@Valid @RequestBody AiModelCreateRequest request) {
        return R.ok(systemConfigService.createAiModel(request));
    }

    @PutMapping("/models/{id}")
    @Operation(summary = "更新AI模型")
    public R<AiModel> updateAiModel(@PathVariable String id,
                                     @Valid @RequestBody AiModelUpdateRequest request) {
        return R.ok(systemConfigService.updateAiModel(id, request));
    }

    @DeleteMapping("/models/{id}")
    @Operation(summary = "删除AI模型")
    public R<Void> deleteAiModel(@PathVariable String id) {
        systemConfigService.deleteAiModel(id);
        return R.ok();
    }

    @PutMapping("/models/{id}/disable")
    @Operation(summary = "停用AI模型并清理路由和模型组引用")
    public R<Void> disableAiModel(@PathVariable String id) {
        systemConfigService.disableAiModel(id);
        return R.ok();
    }

    @PostMapping("/models/{id}/test-connectivity")
    @Operation(summary = "测试AI模型连通性")
    public R<ConnectivityTestResultVO> testConnectivity(@PathVariable String id) {
        return R.ok(systemConfigService.testConnectivity(id));
    }

    @PostMapping("/models/{id}/real-health-check")
    @Operation(summary = "真实协议健康检查AI模型")
    public R<AiModelRealHealthCheckVO> realHealthCheck(@PathVariable String id) {
        return R.ok(aiModelRealHealthCheckService.testRealConnectivity(id));
    }

    // ==================== 路由规则管理 ====================

    @GetMapping("/routes")
    @Operation(summary = "获取路由规则列表")
    public R<List<AiModelRouteVO>> listRoutes() {
        return R.ok(systemConfigService.listRoutes());
    }

    @GetMapping("/routes/{id}")
    @Operation(summary = "获取路由规则详情")
    public R<AiModelRouteVO> getRouteById(@PathVariable String id) {
        return R.ok(systemConfigService.getRouteById(id));
    }

    @PostMapping("/routes")
    @Operation(summary = "创建路由规则")
    public R<AiModelRouteVO> createRoute(@Valid @RequestBody AiModelRouteCreateRequest request) {
        return R.ok(systemConfigService.createRoute(request));
    }

    @PutMapping("/routes/{id}")
    @Operation(summary = "更新路由规则")
    public R<AiModelRouteVO> updateRoute(@PathVariable String id,
                                          @Valid @RequestBody AiModelRouteUpdateRequest request) {
        return R.ok(systemConfigService.updateRoute(id, request));
    }

    @DeleteMapping("/routes/{id}")
    @Operation(summary = "删除路由规则")
    public R<Void> deleteRoute(@PathVariable String id) {
        systemConfigService.deleteRoute(id);
        return R.ok();
    }

    @GetMapping("/routes/analysis")
    @Operation(summary = "获取模型路由分析")
    public R<RouteAnalysisVO> getRouteAnalysis() {
        return R.ok(systemConfigService.getRouteAnalysis());
    }

    // ==================== RAG 管理 ====================

    @GetMapping("/rag/config")
    @Operation(summary = "获取当前租户 RAG 配置")
    public R<RagConfigVO> getRagConfig() {
        return R.ok(ragConfigService.getCurrentTenantConfig());
    }

    @PutMapping("/rag/config")
    @Operation(summary = "更新当前租户 RAG 配置")
    public R<RagConfigVO> updateRagConfig(@Valid @RequestBody RagConfigUpdateRequest request) {
        return R.ok(ragConfigService.updateCurrentTenantConfig(request));
    }

    @GetMapping("/rag/operations")
    @Operation(summary = "查询 RAG 操作日志")
    public R<List<RagOperationLogVO>> listRagOperations(String contextId, String sourceType, Integer limit) {
        return R.ok(ragConfigService.listCurrentTenantLogs(contextId, sourceType, limit));
    }

    // ==================== 团队模板管理 ====================

    @GetMapping("/team-templates")
    @Operation(summary = "获取团队模板列表")
    public R<List<TeamTemplate>> listTeamTemplates() {
        return R.ok(systemConfigService.listTeamTemplates());
    }

    @GetMapping("/team-templates/{code}")
    @Operation(summary = "按code获取团队模板")
    public R<TeamTemplate> getTeamTemplateByCode(@PathVariable String code) {
        return R.ok(systemConfigService.getTeamTemplateByCode(code));
    }
}
