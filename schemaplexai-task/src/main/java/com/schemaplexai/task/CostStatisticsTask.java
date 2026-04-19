package com.schemaplexai.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.BudgetMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.Budget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 成本统计定时任务
 * 定期汇总 Token 消耗数据并检查预算告警
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CostStatisticsTask {

    private final AgentExecutionMapper agentExecutionMapper;
    private final AiModelMapper aiModelMapper;
    private final BudgetMapper budgetMapper;

    /**
     * 每小时执行一次成本聚合与预算告警检查
     * 时间间隔后续改为可配置
     */
    // TODO: cron 表达式改为可配置（如 @Value + @Scheduled(cron = "${...}") ）
    @Scheduled(cron = "0 0 * * * ?")
    public void aggregateCostData() {
        log.info("开始执行成本统计聚合任务...");

        try {
            // 1. 汇总过去1小时的 Token 消耗
            logHourlySummary();

            // 2. 检查预算告警
            checkBudgetAlerts();

            log.info("成本统计聚合任务完成");
        } catch (Exception e) {
            log.error("成本统计聚合任务异常", e);
        }
    }

    /**
     * 汇总并记录过去1小时的 Token 消耗数据
     */
    private void logHourlySummary() {
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusHours(1);

        List<AgentExecution> executions = agentExecutionMapper.selectList(
                new LambdaQueryWrapper<AgentExecution>()
                        .ge(AgentExecution::getCreatedAt, start)
                        .lt(AgentExecution::getCreatedAt, end)
        );

        if (executions.isEmpty()) {
            log.info("过去1小时无执行记录");
            return;
        }

        // 按模型维度汇总
        Map<String, AiModel> modelMap = loadModelMap(executions);

        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        BigDecimal totalCost = BigDecimal.ZERO;

        for (AgentExecution exec : executions) {
            long inputTokens = exec.getTokenInput() != null ? exec.getTokenInput() : 0L;
            long outputTokens = exec.getTokenOutput() != null ? exec.getTokenOutput() : 0L;
            totalInputTokens += inputTokens;
            totalOutputTokens += outputTokens;

            AiModel model = modelMap.get(exec.getAiModel());
            BigDecimal inputPrice = model != null && model.getInputPrice() != null ? model.getInputPrice() : BigDecimal.ZERO;
            BigDecimal outputPrice = model != null && model.getOutputPrice() != null ? model.getOutputPrice() : BigDecimal.ZERO;
            BigDecimal cost = inputPrice.multiply(BigDecimal.valueOf(inputTokens))
                    .add(outputPrice.multiply(BigDecimal.valueOf(outputTokens)))
                    .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
            totalCost = totalCost.add(cost);
        }

        log.info("过去1小时统计: executions={}, inputTokens={}, outputTokens={}, totalCost={}",
                executions.size(), totalInputTokens, totalOutputTokens,
                totalCost.setScale(4, RoundingMode.HALF_UP));
    }

    /**
     * 检查活跃预算是否触发告警阈值
     */
    private void checkBudgetAlerts() {
        List<Budget> activeBudgets = budgetMapper.selectList(
                new LambdaQueryWrapper<Budget>()
                        .eq(Budget::getStatus, "active")
        );

        if (activeBudgets.isEmpty()) {
            return;
        }

        for (Budget budget : activeBudgets) {
            try {
                BigDecimal usedAmount = calculateUsedAmount(budget);
                if (budget.getBudgetAmount() == null || BigDecimal.ZERO.compareTo(budget.getBudgetAmount()) == 0) {
                    continue;
                }

                double usageRate = usedAmount.divide(budget.getBudgetAmount(), 4, RoundingMode.HALF_UP)
                        .doubleValue() * 100;

                // 根据阈值判断告警级别（alertThreshold 为 Boolean，表示是否启用该级别告警）
                boolean alert100 = Boolean.TRUE.equals(budget.getAlertThreshold100());
                boolean alert80 = Boolean.TRUE.equals(budget.getAlertThreshold80());
                boolean alert50 = Boolean.TRUE.equals(budget.getAlertThreshold50());

                String formattedRate = String.format("%.1f", usageRate);
                if (alert100 && usageRate >= 100) {
                    log.warn("预算超限: budgetId={}, target={}, usageRate={}%, overLimitStrategy={}",
                            budget.getId(), budget.getTargetName(), formattedRate, budget.getOverLimitStrategy());
                } else if (alert80 && usageRate >= 80) {
                    log.warn("预算告警(80%): budgetId={}, target={}, usageRate={}%",
                            budget.getId(), budget.getTargetName(), formattedRate);
                } else if (alert50 && usageRate >= 50) {
                    log.info("预算提醒(50%): budgetId={}, target={}, usageRate={}%",
                            budget.getId(), budget.getTargetName(), formattedRate);
                }
            } catch (Exception e) {
                log.error("预算告警检查异常: budgetId={}", budget.getId(), e);
            }
        }
    }

    /**
     * 计算预算已用金额
     */
    private BigDecimal calculateUsedAmount(Budget budget) {
        // 根据预算周期确定时间范围
        LocalDateTime start = resolveCycleStart(budget.getBudgetCycle());
        LocalDateTime end = LocalDateTime.now();

        // 查询该周期内的执行记录
        LambdaQueryWrapper<AgentExecution> wrapper = new LambdaQueryWrapper<AgentExecution>()
                .ge(AgentExecution::getCreatedAt, start)
                .lt(AgentExecution::getCreatedAt, end);

        // 按预算级别过滤
        String targetId = budget.getTargetId();
        if ("project".equals(budget.getBudgetLevel())) {
            wrapper.eq(AgentExecution::getSpecId, targetId);
        } else if ("user".equals(budget.getBudgetLevel())) {
            wrapper.eq(AgentExecution::getCreatedBy, targetId);
        }
        // enterprise 和 team 级别不过滤，汇总全部

        List<AgentExecution> executions = agentExecutionMapper.selectList(wrapper);
        Map<String, AiModel> modelMap = loadModelMap(executions);

        BigDecimal totalCost = BigDecimal.ZERO;
        for (AgentExecution exec : executions) {
            long inputTokens = exec.getTokenInput() != null ? exec.getTokenInput() : 0L;
            long outputTokens = exec.getTokenOutput() != null ? exec.getTokenOutput() : 0L;
            AiModel model = modelMap.get(exec.getAiModel());
            BigDecimal inputPrice = model != null && model.getInputPrice() != null ? model.getInputPrice() : BigDecimal.ZERO;
            BigDecimal outputPrice = model != null && model.getOutputPrice() != null ? model.getOutputPrice() : BigDecimal.ZERO;
            BigDecimal cost = inputPrice.multiply(BigDecimal.valueOf(inputTokens))
                    .add(outputPrice.multiply(BigDecimal.valueOf(outputTokens)))
                    .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
            totalCost = totalCost.add(cost);
        }

        return totalCost;
    }

    /**
     * 根据预算周期计算周期起始时间
     */
    private LocalDateTime resolveCycleStart(String budgetCycle) {
        LocalDate today = LocalDate.now();
        return switch (budgetCycle != null ? budgetCycle.toLowerCase() : "monthly") {
            case "daily" -> today.atStartOfDay();
            case "quarterly" -> {
                int quarterStartMonth = ((today.getMonthValue() - 1) / 3) * 3 + 1;
                yield LocalDate.of(today.getYear(), quarterStartMonth, 1).atStartOfDay();
            }
            default -> YearMonth.from(today).atDay(1).atStartOfDay(); // monthly
        };
    }

    /**
     * 加载模型ID→模型实体的映射
     */
    private Map<String, AiModel> loadModelMap(List<AgentExecution> executions) {
        List<String> modelIds = executions.stream()
                .map(AgentExecution::getAiModel)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (modelIds.isEmpty()) {
            return Map.of();
        }
        return aiModelMapper.selectBatchIds(modelIds).stream()
                .collect(Collectors.toMap(AiModel::getId, Function.identity(), (a, b) -> a));
    }
}
