package com.schemaplexai.service.cost.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.BudgetMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.model.converter.BudgetConverter;
import com.schemaplexai.model.dto.cost.BudgetCreateRequest;
import com.schemaplexai.model.dto.cost.BudgetQueryRequest;
import com.schemaplexai.model.dto.cost.BudgetUpdateRequest;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.Budget;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.vo.cost.BudgetAlertVO;
import com.schemaplexai.model.vo.cost.BudgetUsageVO;
import com.schemaplexai.model.vo.cost.BudgetVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.cost.BudgetService;
import com.schemaplexai.service.cost.validator.BudgetValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Month;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 预算管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetServiceImpl implements BudgetService {

    private final BudgetMapper budgetMapper;
    private final BudgetConverter budgetConverter;
    private final BudgetValidator budgetValidator;
    private final EntityValidator entityValidator;
    private final AgentExecutionMapper agentExecutionMapper;
    private final AiModelMapper aiModelMapper;
    private final SpecMapper specMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BudgetVO create(BudgetCreateRequest request) {
        request.setBudgetLevel(normalizeValue(request.getBudgetLevel()));
        request.setBudgetCycle(normalizeValue(request.getBudgetCycle()));
        request.setOverLimitStrategy(normalizeValue(request.getOverLimitStrategy()));
        request.setTargetId(resolveTargetId(request.getBudgetLevel(), request.getTargetId()));

        // 校验金额合法性
        budgetValidator.validateAmountPositive(request.getBudgetAmount());
        // 校验预算周期唯一性
        budgetValidator.validateCycleUnique(
                request.getBudgetLevel(), request.getTargetId(), request.getBudgetCycle());

        // 构建实体（MapStruct 自动设置 status="active"）
        var budget = budgetConverter.fromCreateRequest(request);
        budget.setTenantId(SecurityUtil.getCurrentTenantId());
        budget.setCreatedBy(SecurityUtil.getCurrentUserId());

        budgetMapper.insert(budget);
        log.info("创建预算成功: budgetId={}, level={}, targetId={}",
                budget.getId(), budget.getBudgetLevel(), budget.getTargetId());

        return budgetConverter.toVO(budget);
    }

    @Override
    public PageResult<BudgetVO> page(BudgetQueryRequest request) {
        var page = new Page<Budget>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<Budget>();

        // 可选条件过滤
        if (StringUtils.hasText(request.getBudgetLevel())) {
            wrapper.eq(Budget::getBudgetLevel, request.getBudgetLevel());
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(Budget::getStatus, request.getStatus());
        }
        wrapper.orderByDesc(Budget::getCreatedAt);

        var result = budgetMapper.selectPage(page, wrapper);
        var voList = budgetConverter.toVOList(result.getRecords());
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BudgetVO update(String id, BudgetUpdateRequest request) {
        var budget = entityValidator.requireExists(budgetMapper, id, ResultCode.BUDGET_NOT_FOUND);

        // 如果更新了金额，校验合法性
        if (request.getBudgetAmount() != null) {
            budgetValidator.validateAmountPositive(request.getBudgetAmount());
        }

        // 逐字段更新（仅更新非空值）
        var updateEntity = new Budget();
        updateEntity.setId(id);
        if (request.getBudgetAmount() != null) {
            updateEntity.setBudgetAmount(request.getBudgetAmount());
        }
        if (request.getAlertThreshold50() != null) {
            updateEntity.setAlertThreshold50(request.getAlertThreshold50());
        }
        if (request.getAlertThreshold80() != null) {
            updateEntity.setAlertThreshold80(request.getAlertThreshold80());
        }
        if (request.getAlertThreshold100() != null) {
            updateEntity.setAlertThreshold100(request.getAlertThreshold100());
        }
        if (StringUtils.hasText(request.getOverLimitStrategy())) {
            updateEntity.setOverLimitStrategy(normalizeValue(request.getOverLimitStrategy()));
        }
        if (StringUtils.hasText(request.getStatus())) {
            updateEntity.setStatus(normalizeValue(request.getStatus()));
        }
        updateEntity.setUpdatedBy(SecurityUtil.getCurrentUserId());
        updateEntity.setUpdatedAt(LocalDateTime.now());

        budgetMapper.updateById(updateEntity);
        log.info("更新预算成功: budgetId={}", id);

        return budgetConverter.toVO(budgetMapper.selectById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        entityValidator.requireExists(budgetMapper, id, ResultCode.BUDGET_NOT_FOUND);
        budgetMapper.deleteById(id);
        log.info("删除预算成功: budgetId={}", id);
    }

    @Override
    public BudgetUsageVO getUsage(String id) {
        Budget budget = entityValidator.requireExists(budgetMapper, id, ResultCode.BUDGET_NOT_FOUND);
        return buildUsage(budget);
    }

    @Override
    public List<BudgetAlertVO> getAlerts(Integer page, Integer size) {
        log.info("查询预算告警: page={}, size={}", page, size);
        int pageNo = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? 20 : size;
        List<Budget> activeBudgets = budgetMapper.selectList(new LambdaQueryWrapper<Budget>()
                .eq(Budget::getStatus, "active")
                .orderByDesc(Budget::getCreatedAt));
        List<BudgetAlertVO> alerts = activeBudgets.stream()
                .map(budget -> new BudgetAlertCandidate(budget, buildUsage(budget)))
                .filter(candidate -> !"normal".equalsIgnoreCase(candidate.usage().getAlertLevel()))
                .sorted(Comparator.comparing(
                        (BudgetAlertCandidate candidate) -> candidate.usage().getUsageRate(),
                        Comparator.nullsLast(Double::compareTo)
                ).reversed())
                .map(candidate -> {
                    BudgetAlertVO alert = new BudgetAlertVO();
                    alert.setBudgetId(candidate.budget().getId());
                    alert.setBudgetLevel(candidate.budget().getBudgetLevel());
                    alert.setTargetName(StringUtils.hasText(candidate.budget().getTargetName())
                            ? candidate.budget().getTargetName()
                            : candidate.budget().getTargetId());
                    alert.setUsageRate(candidate.usage().getUsageRate());
                    alert.setAlertLevel(candidate.usage().getAlertLevel());
                    alert.setAlertTime(LocalDateTime.now());
                    return alert;
                })
                .toList();
        if (alerts.isEmpty()) {
            return Collections.emptyList();
        }
        int fromIndex = Math.min((pageNo - 1) * pageSize, alerts.size());
        int toIndex = Math.min(fromIndex + pageSize, alerts.size());
        return alerts.subList(fromIndex, toIndex);
    }

    private BudgetUsageVO buildUsage(Budget budget) {
        TimeWindow cycle = resolveCycle(normalizeValue(budget.getBudgetCycle()));
        BigDecimal usedAmount = calculateUsedAmount(budget, cycle.start(), cycle.end());
        BigDecimal remainAmount = budget.getBudgetAmount().subtract(usedAmount).setScale(2, RoundingMode.HALF_UP);
        double usageRate = budget.getBudgetAmount() == null || BigDecimal.ZERO.compareTo(budget.getBudgetAmount()) == 0
                ? 0D
                : roundTwoDecimal(usedAmount.multiply(BigDecimal.valueOf(100))
                .divide(budget.getBudgetAmount(), 4, RoundingMode.HALF_UP)
                .doubleValue());

        BudgetUsageVO vo = new BudgetUsageVO();
        vo.setBudgetAmount(scaleMoney(budget.getBudgetAmount()));
        vo.setUsedAmount(scaleMoney(usedAmount));
        vo.setRemainAmount(remainAmount);
        vo.setUsageRate(usageRate);
        vo.setAlertLevel(resolveAlertLevel(budget, usageRate));
        vo.setCycleStart(cycle.start());
        vo.setCycleEnd(cycle.end());
        return vo;
    }

    private String resolveTargetId(String budgetLevel, String targetId) {
        String normalizedLevel = normalizeValue(budgetLevel);
        if ("enterprise".equals(normalizedLevel)) {
            return SecurityUtil.getCurrentTenantId();
        }
        return normalizeValue(targetId);
    }

    private BigDecimal calculateUsedAmount(Budget budget, LocalDateTime start, LocalDateTime end) {
        List<AgentExecution> executions = agentExecutionMapper.selectList(new LambdaQueryWrapper<AgentExecution>()
                .ge(AgentExecution::getCreatedAt, start)
                .lt(AgentExecution::getCreatedAt, end)
                .orderByDesc(AgentExecution::getCreatedAt));
        Map<String, Spec> specMap = loadSpecMap(executions.stream()
                .map(AgentExecution::getSpecId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList());
        Map<String, AiModel> modelMap = loadModelMap(executions.stream()
                .map(AgentExecution::getAiModel)
                .filter(StringUtils::hasText)
                .distinct()
                .toList());
        return executions.stream()
                .filter(execution -> matchBudget(budget, execution, specMap.get(execution.getSpecId())))
                .map(execution -> estimateCost(execution, modelMap.get(execution.getAiModel())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean matchBudget(Budget budget, AgentExecution execution, Spec spec) {
        String level = normalizeValue(budget.getBudgetLevel());
        String targetId = budget.getTargetId();
        if (!StringUtils.hasText(level) || !StringUtils.hasText(targetId)) {
            return false;
        }
        if ("enterprise".equals(level)) {
            return true;
        }
        if ("project".equals(level)) {
            if (spec == null) {
                return false;
            }
            return Objects.equals(spec.getProjectId(), targetId)
                    || (spec.getWorkspaceIds() != null && spec.getWorkspaceIds().contains(targetId));
        }
        if ("user".equals(level)) {
            return Objects.equals(execution.getCreatedBy(), targetId);
        }
        if ("team".equals(level)) {
            return Objects.equals(execution.getCreatedBy(), targetId);
        }
        return false;
    }

    private BigDecimal estimateCost(AgentExecution execution, AiModel model) {
        long tokenInput = execution.getTokenInput() == null ? 0L : execution.getTokenInput();
        long tokenOutput = execution.getTokenOutput() == null ? 0L : execution.getTokenOutput();
        BigDecimal inputPrice = model == null || model.getInputPrice() == null ? BigDecimal.ZERO : model.getInputPrice();
        BigDecimal outputPrice = model == null || model.getOutputPrice() == null ? BigDecimal.ZERO : model.getOutputPrice();
        return inputPrice.multiply(BigDecimal.valueOf(tokenInput))
                .add(outputPrice.multiply(BigDecimal.valueOf(tokenOutput)))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
    }

    private Map<String, Spec> loadSpecMap(List<String> specIds) {
        if (specIds == null || specIds.isEmpty()) {
            return Map.of();
        }
        return specMapper.selectBatchIds(new ArrayList<>(specIds)).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Spec::getId, spec -> spec, (left, right) -> left, LinkedHashMap::new));
    }

    private Map<String, AiModel> loadModelMap(List<String> modelKeys) {
        if (modelKeys == null || modelKeys.isEmpty()) {
            return Map.of();
        }
        List<String> keys = modelKeys.stream().filter(StringUtils::hasText).distinct().toList();
        if (keys.isEmpty()) {
            return Map.of();
        }
        List<String> idKeys = keys.stream()
                .filter(this::looksLikeUuid)
                .toList();
        List<String> nameKeys = keys.stream()
                .filter(key -> !looksLikeUuid(key))
                .toList();
        List<AiModel> models = new ArrayList<>();
        if (!idKeys.isEmpty()) {
            models.addAll(aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                    .in(AiModel::getId, idKeys)));
        }
        if (!nameKeys.isEmpty()) {
            models.addAll(aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                    .in(AiModel::getName, nameKeys)));
        }
        Map<String, AiModel> result = new LinkedHashMap<>();
        for (AiModel model : models) {
            if (model == null) {
                continue;
            }
            if (StringUtils.hasText(model.getId())) {
                result.putIfAbsent(model.getId(), model);
            }
            if (StringUtils.hasText(model.getName())) {
                result.putIfAbsent(model.getName(), model);
            }
        }
        return result;
    }

    private boolean looksLikeUuid(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return value.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    }

    private String resolveAlertLevel(Budget budget, double usageRate) {
        if (usageRate >= 100 && Boolean.TRUE.equals(budget.getAlertThreshold100())) {
            return "exceeded";
        }
        if (usageRate >= 80 && Boolean.TRUE.equals(budget.getAlertThreshold80())) {
            return "warning_80";
        }
        if (usageRate >= 50 && Boolean.TRUE.equals(budget.getAlertThreshold50())) {
            return "warning_50";
        }
        return "normal";
    }

    private TimeWindow resolveCycle(String budgetCycle) {
        LocalDateTime now = LocalDateTime.now();
        if ("daily".equalsIgnoreCase(budgetCycle)) {
            LocalDateTime start = now.toLocalDate().atStartOfDay();
            return new TimeWindow(start, start.plusDays(1));
        }
        if ("quarterly".equalsIgnoreCase(budgetCycle)) {
            int startMonth = ((now.getMonthValue() - 1) / 3) * 3 + 1;
            LocalDateTime start = LocalDateTime.of(now.getYear(), Month.of(startMonth), 1, 0, 0);
            return new TimeWindow(start, start.plusMonths(3));
        }
        LocalDateTime start = LocalDateTime.of(now.getYear(), now.getMonth(), 1, 0, 0);
        return new TimeWindow(start, start.plusMonths(1));
    }

    private BigDecimal scaleMoney(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP);
    }

    private double roundTwoDecimal(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private String normalizeValue(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase() : value;
    }

    private record TimeWindow(LocalDateTime start, LocalDateTime end) {
    }

    private record BudgetAlertCandidate(Budget budget, BudgetUsageVO usage) {
    }
}
