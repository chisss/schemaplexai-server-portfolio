package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.monitor.AuditLogQueryRequest;
import com.schemaplexai.model.dto.monitor.ReportQueryRequest;
import com.schemaplexai.model.dto.monitor.ReportTemplateCreateRequest;
import com.schemaplexai.model.vo.monitor.ActiveAgentVO;
import com.schemaplexai.model.vo.monitor.AgentTraceSpanVO;
import com.schemaplexai.model.vo.monitor.AgentTraceVO;
import com.schemaplexai.model.vo.monitor.AuditLogVO;
import com.schemaplexai.model.vo.monitor.DashboardVO;
import com.schemaplexai.model.vo.monitor.AgentTraceFailureSummaryVO;
import com.schemaplexai.model.vo.monitor.ReportDataVO;
import com.schemaplexai.model.vo.monitor.ReportTemplateVO;
import com.schemaplexai.model.vo.monitor.SystemHealthVO;
import com.schemaplexai.model.vo.monitor.TaskQueueVO;
import com.schemaplexai.service.monitor.AgentTraceService;
import com.schemaplexai.service.monitor.AuditLogService;
import com.schemaplexai.service.monitor.DashboardService;
import com.schemaplexai.service.monitor.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 监控报表控制器
 */
@RestController
@RequestMapping("/monitor")
@RequiredArgsConstructor
@Tag(name = "监控报表")
public class MonitorController {

    private final DashboardService dashboardService;
    private final ReportService reportService;
    private final AuditLogService auditLogService;
    private final AgentTraceService agentTraceService;

    // ==================== 仪表盘 ====================

    @GetMapping("/dashboard")
    @Operation(summary = "获取监控仪表盘")
    public R<DashboardVO> getDashboard() {
        return R.ok(dashboardService.getDashboard());
    }

    @GetMapping("/agents/active")
    @Operation(summary = "获取活跃Agent列表")
    public R<List<ActiveAgentVO>> getActiveAgents() {
        return R.ok(dashboardService.getActiveAgents());
    }

    @GetMapping("/tasks/queue")
    @Operation(summary = "获取任务队列状态")
    public R<TaskQueueVO> getTaskQueue() {
        return R.ok(dashboardService.getTaskQueue());
    }

    @GetMapping("/system/health")
    @Operation(summary = "获取系统健康状态")
    public R<SystemHealthVO> getSystemHealth() {
        return R.ok(dashboardService.getSystemHealth());
    }

    // ==================== 报表 ====================

    @GetMapping("/reports/efficiency")
    @Operation(summary = "效率报表")
    public R<ReportDataVO> getEfficiencyReport(ReportQueryRequest request) {
        return R.ok(reportService.getEfficiencyReport(request));
    }

    @GetMapping("/reports/quality")
    @Operation(summary = "质量报表")
    public R<ReportDataVO> getQualityReport(ReportQueryRequest request) {
        return R.ok(reportService.getQualityReport(request));
    }

    @GetMapping("/reports/agent-performance")
    @Operation(summary = "Agent性能报表")
    public R<ReportDataVO> getAgentPerformanceReport(ReportQueryRequest request,
                                                     @RequestParam(required = false) String agentIds) {
        return R.ok(reportService.getAgentPerformanceReport(request, agentIds));
    }

    @GetMapping("/reports/project-progress")
    @Operation(summary = "项目进度报表")
    public R<ReportDataVO> getProjectProgressReport(ReportQueryRequest request) {
        return R.ok(reportService.getProjectProgressReport(request));
    }

    @GetMapping("/reports/team-collaboration")
    @Operation(summary = "团队协作报表")
    public R<ReportDataVO> getTeamCollaborationReport(ReportQueryRequest request) {
        return R.ok(reportService.getTeamCollaborationReport(request));
    }

    @PostMapping("/reports/custom")
    @Operation(summary = "自定义报表查询")
    public R<ReportDataVO> customReport(@RequestBody Map<String, Object> queryConfig) {
        return R.ok(reportService.customQuery(queryConfig));
    }

    // ==================== 报表模板 ====================

    @GetMapping("/reports/templates")
    @Operation(summary = "分页查询报表模板")
    public R<PageResult<ReportTemplateVO>> pageTemplates(
            @RequestParam(required = false) String reportType,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return R.ok(reportService.pageTemplates(reportType, page, size));
    }

    @PostMapping("/reports/templates")
    @Operation(summary = "创建报表模板")
    public R<ReportTemplateVO> createTemplate(@Valid @RequestBody ReportTemplateCreateRequest request) {
        return R.ok(reportService.createTemplate(request));
    }

    // ==================== 审计日志 ====================

    @GetMapping("/audit-logs")
    @Operation(summary = "分页查询审计日志")
    public R<PageResult<AuditLogVO>> pageAuditLogs(AuditLogQueryRequest request) {
        return R.ok(auditLogService.page(request));
    }

    @GetMapping("/audit-logs/{id}")
    @Operation(summary = "获取审计日志详情")
    public R<AuditLogVO> getAuditLog(@PathVariable String id) {
        return R.ok(auditLogService.getById(id));
    }

    @GetMapping("/traces/agent/{agentId}")
    @Operation(summary = "查询 Agent 调用链列表")
    public R<List<AgentTraceVO>> getAgentTraces(@PathVariable String agentId) {
        return R.ok(agentTraceService.listAgentTraces(agentId));
    }

    @GetMapping("/traces/recent")
    @Operation(summary = "查询最近调用链列表")
    public R<List<AgentTraceVO>> getRecentTraces() {
        return R.ok(agentTraceService.listAgentTraces(null));
    }

    @GetMapping("/traces/{traceId}/spans")
    @Operation(summary = "查询单次执行 Span")
    public R<List<AgentTraceSpanVO>> getTraceSpans(@PathVariable String traceId) {
        return R.ok(agentTraceService.listTraceSpans(traceId));
    }

    @GetMapping("/traces/{traceId}/failure-summary")
    @Operation(summary = "查询单次执行失败分类摘要")
    public R<AgentTraceFailureSummaryVO> getTraceFailureSummary(@PathVariable String traceId) {
        return R.ok(agentTraceService.summarizeFailures(traceId));
    }
}
