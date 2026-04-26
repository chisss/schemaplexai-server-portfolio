package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.cost.BudgetCreateRequest;
import com.schemaplexai.model.dto.cost.BudgetQueryRequest;
import com.schemaplexai.model.dto.cost.BudgetUpdateRequest;
import com.schemaplexai.model.vo.cost.BudgetAlertVO;
import com.schemaplexai.model.vo.cost.BudgetUsageVO;
import com.schemaplexai.model.vo.cost.BudgetVO;
import com.schemaplexai.model.vo.cost.CostByDimensionVO;
import com.schemaplexai.model.vo.cost.CostOverviewVO;
import com.schemaplexai.model.vo.cost.CostTrendVO;
import com.schemaplexai.service.cost.BudgetService;
import com.schemaplexai.service.cost.CostAnalysisService;
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

/**
 * 成本管控控制器
 */
@RestController
@RequestMapping("/costs")
@RequiredArgsConstructor
@Tag(name = "成本管控")
public class CostController {

    private final CostAnalysisService costAnalysisService;
    private final BudgetService budgetService;

    // ==================== 成本分析 ====================

    @GetMapping("/overview")
    @Operation(summary = "成本概览")
    public R<CostOverviewVO> overview(@RequestParam(defaultValue = "7d") String timeRange) {
        return R.ok(costAnalysisService.overview(timeRange));
    }

    @GetMapping("/trend")
    @Operation(summary = "成本趋势")
    public R<CostTrendVO> trend(
            @RequestParam(defaultValue = "7d") String timeRange,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "day") String groupBy) {
        return R.ok(costAnalysisService.trend(timeRange, startTime, endTime, groupBy));
    }

    @GetMapping("/by-project")
    @Operation(summary = "按项目维度分析成本")
    public R<List<CostByDimensionVO>> byProject(
            @RequestParam(defaultValue = "7d") String timeRange,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        return R.ok(costAnalysisService.byProject(timeRange, startTime, endTime));
    }

    @GetMapping("/by-model")
    @Operation(summary = "按模型维度分析成本")
    public R<List<CostByDimensionVO>> byModel(
            @RequestParam(defaultValue = "7d") String timeRange,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        return R.ok(costAnalysisService.byModel(timeRange, startTime, endTime));
    }

    @GetMapping("/by-agent")
    @Operation(summary = "按Agent维度分析成本")
    public R<List<CostByDimensionVO>> byAgent(
            @RequestParam(defaultValue = "7d") String timeRange,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        return R.ok(costAnalysisService.byAgent(timeRange, startTime, endTime));
    }

    @GetMapping("/by-user")
    @Operation(summary = "按用户维度分析成本")
    public R<List<CostByDimensionVO>> byUser(
            @RequestParam(defaultValue = "7d") String timeRange,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        return R.ok(costAnalysisService.byUser(timeRange, startTime, endTime));
    }

    // ==================== 预算管理 ====================

    @PostMapping("/budgets")
    @Operation(summary = "创建预算")
    public R<BudgetVO> createBudget(@Valid @RequestBody BudgetCreateRequest request) {
        return R.ok(budgetService.create(request));
    }

    @GetMapping("/budget")
    @Operation(summary = "获取当前租户预算配置")
    public R<BudgetVO> getCurrentBudget() {
        return R.ok(budgetService.getCurrentBudget());
    }

    @PutMapping("/budget")
    @Operation(summary = "更新当前租户预算配置")
    public R<BudgetVO> upsertCurrentBudget(@RequestBody BudgetUpdateRequest request) {
        return R.ok(budgetService.upsertCurrentBudget(request));
    }

    @GetMapping("/budgets")
    @Operation(summary = "分页查询预算")
    public R<PageResult<BudgetVO>> pageBudgets(BudgetQueryRequest request) {
        return R.ok(budgetService.page(request));
    }

    @PutMapping("/budgets/{id}")
    @Operation(summary = "更新预算")
    public R<BudgetVO> updateBudget(@PathVariable String id,
                                    @Valid @RequestBody BudgetUpdateRequest request) {
        return R.ok(budgetService.update(id, request));
    }

    @DeleteMapping("/budgets/{id}")
    @Operation(summary = "删除预算")
    public R<Void> deleteBudget(@PathVariable String id) {
        budgetService.delete(id);
        return R.ok();
    }

    @GetMapping("/budgets/{id}/usage")
    @Operation(summary = "获取预算使用情况")
    public R<BudgetUsageVO> getBudgetUsage(@PathVariable String id) {
        return R.ok(budgetService.getUsage(id));
    }

    @GetMapping("/budget/usage")
    @Operation(summary = "获取当前租户预算使用情况")
    public R<BudgetUsageVO> getCurrentBudgetUsage() {
        return R.ok(budgetService.getCurrentBudgetUsage());
    }

    @GetMapping("/budgets/alerts")
    @Operation(summary = "获取预算告警列表")
    public R<List<BudgetAlertVO>> getBudgetAlerts(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return R.ok(budgetService.getAlerts(page, size));
    }
}
