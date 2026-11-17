package com.schemaplexai.service.cost.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.AuditLogMapper;
import com.schemaplexai.dao.mapper.BudgetMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.AuditLog;
import com.schemaplexai.model.entity.Budget;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.vo.cost.BudgetAdmissionVO;
import com.schemaplexai.model.vo.cost.BudgetBlockEventVO;
import com.schemaplexai.service.cost.BudgetAdmissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 预算执行准入服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetAdmissionServiceImpl implements BudgetAdmissionService {

    private static final String STRATEGY_ALLOW = "allow";
    private static final String STRATEGY_ALERT = "alert";
    private static final String STRATEGY_DOWNGRADE = "downgrade";
    private static final String STRATEGY_BLOCK = "block";
    private static final Pattern JSON_STRING_FIELD_PATTERN = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern JSON_NUMBER_FIELD_PATTERN = Pattern.compile("\"%s\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");

    private final BudgetMapper budgetMapper;
    private final AgentExecutionMapper agentExecutionMapper;
    private final AiModelMapper aiModelMapper;
    private final SpecMapper specMapper;
    private final AuditLogMapper auditLogMapper;

    @Override
    public BudgetAdmissionVO evaluateForAgentExecution(String agentId, String specId, String userId, String model) {
        return evaluateForTenantAgentExecution(requireTenantId(), agentId, specId, userId, model);
    }

    @Override
    public BudgetAdmissionVO evaluateForTenantAgentExecution(String tenantId,
                                                            String agentId,
                                                            String specId,
                                                            String userId,
                                                            String model) {
        String normalizedTenantId = requireTenantId(tenantId);
        String actorUserId = StringUtils.hasText(userId) ? userId : SecurityUtil.getCurrentUserId();
        List<Budget> budgets = budgetMapper.selectList(new LambdaQueryWrapper<Budget>()
                .eq(Budget::getTenantId, normalizedTenantId)
                .eq(Budget::getStatus, "active")
                .orderByDesc(Budget::getCreatedAt));
        if (CollectionUtils.isEmpty(budgets)) {
            return allowWithoutBudget();
        }

        Map<String, Spec> specMap = loadSpecMap(budgets, specId);
        Spec matchedSpec = StringUtils.hasText(specId) ? specMap.get(specId) : null;
        return budgets.stream()
                .filter(budget -> matchBudget(budget, agentId, specId, actorUserId, matchedSpec))
                .map(budget -> buildAdmission(normalizedTenantId, budget))
                .max(Comparator.comparingInt(this::priorityOf))
                .orElseGet(this::allowWithoutBudget);
    }

    @Override
    public BudgetAdmissionVO getCurrentAdmissionStatus() {
        return evaluateForAgentExecution(null, null, SecurityUtil.getCurrentUserId(), null);
    }

    @Override
    public void recordAdmissionAudit(BudgetAdmissionVO admission, String resourceType, String resourceId) {
        recordAdmissionAudit(requireTenantId(), admission, resourceType, resourceId);
    }

    @Override
    public void recordAdmissionAudit(String tenantId, BudgetAdmissionVO admission, String resourceType, String resourceId) {
        if (admission == null || !Boolean.TRUE.equals(admission.getActionRequired())) {
            return;
        }
        try {
            AuditLog log = new AuditLog();
            log.setTenantId(requireTenantId(tenantId));
            log.setUserId(SecurityUtil.getCurrentUserId());
            log.setUsername(SecurityUtil.getCurrentUsername());
            log.setAction("budget." + normalize(admission.getStrategy()));
            log.setResource(StringUtils.hasText(resourceType) ? resourceType : "agent_execution");
            log.setResourceId(resourceId);
            log.setDetail(buildAuditDetail(admission));
            log.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(log);
        } catch (Exception exception) {
            log.warn("写入预算准入审计失败（已忽略）: resourceType={}, resourceId={}, error={}",
                    resourceType, resourceId, exception.getMessage());
        }
    }

    @Override
    public List<BudgetBlockEventVO> listRecentBlockEvents(Integer limit) {
        int resolvedLimit = limit == null || limit < 1 ? 10 : Math.min(limit, 50);
        List<AuditLog> logs = auditLogMapper.selectList(new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getTenantId, requireTenantId())
                .eq(AuditLog::getAction, "budget.block")
                .orderByDesc(AuditLog::getCreatedAt)
                .last("LIMIT " + resolvedLimit));
        return logs.stream().map(this::toBlockEvent).toList();
    }

    private BudgetAdmissionVO buildAdmission(String tenantId, Budget budget) {
        TimeWindow cycle = resolveCycle(normalize(budget.getBudgetCycle()));
        BigDecimal usedAmount = calculateUsedAmount(tenantId, budget, cycle.start(), cycle.end());
        BigDecimal budgetAmount = scaleMoney(budget.getBudgetAmount());
        BigDecimal remainingAmount = budgetAmount.subtract(usedAmount).setScale(2, RoundingMode.HALF_UP);
        double usageRate = BigDecimal.ZERO.compareTo(budgetAmount) == 0
                ? 0D
                : roundTwoDecimal(usedAmount.multiply(BigDecimal.valueOf(100))
                .divide(budgetAmount, 4, RoundingMode.HALF_UP)
                .doubleValue());

        BudgetAdmissionVO admission = baseAdmission(budget, budgetAmount, usedAmount, remainingAmount, usageRate);
        if (usageRate >= 100D) {
            String strategy = normalizeOrDefault(budget.getOverLimitStrategy(), STRATEGY_ALERT);
            admission.setStrategy(strategy);
            admission.setAllowed(!STRATEGY_BLOCK.equals(strategy));
            admission.setActionRequired(Boolean.TRUE);
            admission.setMessage(resolveOverLimitMessage(strategy, usageRate, remainingAmount));
            return admission;
        }
        if (isAlertThresholdHit(budget, usageRate)) {
            admission.setStrategy(STRATEGY_ALERT);
            admission.setAllowed(Boolean.TRUE);
            admission.setActionRequired(Boolean.TRUE);
            admission.setMessage("预算使用率已达到 " + usageRate + "%，建议关注后续执行成本。");
            return admission;
        }
        admission.setStrategy(STRATEGY_ALLOW);
        admission.setAllowed(Boolean.TRUE);
        admission.setActionRequired(Boolean.FALSE);
        admission.setMessage("预算容量充足，允许执行。");
        return admission;
    }

    private BudgetAdmissionVO baseAdmission(Budget budget,
                                            BigDecimal budgetAmount,
                                            BigDecimal usedAmount,
                                            BigDecimal remainingAmount,
                                            double usageRate) {
        BudgetAdmissionVO admission = new BudgetAdmissionVO();
        admission.setBudgetId(budget.getId());
        admission.setBudgetAmount(budgetAmount);
        admission.setUsedAmount(scaleMoney(usedAmount));
        admission.setRemainingAmount(remainingAmount);
        admission.setUsageRate(usageRate);
        return admission;
    }

    private String resolveOverLimitMessage(String strategy, double usageRate, BigDecimal remainingAmount) {
        if (STRATEGY_BLOCK.equals(strategy)) {
            return "预算已超限（使用率 " + usageRate + "%，剩余 " + remainingAmount + "），本次执行已阻断。";
        }
        if (STRATEGY_DOWNGRADE.equals(strategy)) {
            return "预算已超限（使用率 " + usageRate + "%），本次执行允许进入降级策略。";
        }
        return "预算已超限（使用率 " + usageRate + "%），本次执行允许继续但需要关注成本风险。";
    }

    private BigDecimal calculateUsedAmount(String tenantId, Budget budget, LocalDateTime start, LocalDateTime end) {
        List<AgentExecution> executions = agentExecutionMapper.selectList(new LambdaQueryWrapper<AgentExecution>()
                .eq(AgentExecution::getTenantId, tenantId)
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
                .filter(execution -> matchBudget(budget, execution,
                        StringUtils.hasText(execution.getSpecId()) ? specMap.get(execution.getSpecId()) : null))
                .map(execution -> estimateCost(execution,
                        StringUtils.hasText(execution.getAiModel()) ? modelMap.get(execution.getAiModel()) : null))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private boolean matchBudget(Budget budget, String agentId, String specId, String userId, Spec spec) {
        String level = normalize(budget.getBudgetLevel());
        String targetId = budget.getTargetId();
        if (!StringUtils.hasText(level)) {
            return false;
        }
        if ("enterprise".equals(level)) {
            return true;
        }
        if ("project".equals(level)) {
            return spec != null && (Objects.equals(spec.getProjectId(), targetId)
                    || (spec.getWorkspaceIds() != null && spec.getWorkspaceIds().contains(targetId)));
        }
        if ("user".equals(level) || "team".equals(level)) {
            return StringUtils.hasText(userId) && Objects.equals(userId, targetId);
        }
        if ("agent".equals(level)) {
            return StringUtils.hasText(agentId) && Objects.equals(agentId, targetId);
        }
        return false;
    }

    private boolean matchBudget(Budget budget, AgentExecution execution, Spec spec) {
        String level = normalize(budget.getBudgetLevel());
        String targetId = budget.getTargetId();
        if (!StringUtils.hasText(level)) {
            return false;
        }
        if ("enterprise".equals(level)) {
            return true;
        }
        if ("project".equals(level)) {
            return spec != null && (Objects.equals(spec.getProjectId(), targetId)
                    || (spec.getWorkspaceIds() != null && spec.getWorkspaceIds().contains(targetId)));
        }
        if ("user".equals(level) || "team".equals(level)) {
            return Objects.equals(execution.getCreatedBy(), targetId);
        }
        if ("agent".equals(level)) {
            return Objects.equals(execution.getAgentId(), targetId);
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
        if (CollectionUtils.isEmpty(specIds)) {
            return Map.of();
        }
        return specMapper.selectBatchIds(new ArrayList<>(specIds)).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Spec::getId, spec -> spec, (left, right) -> left, LinkedHashMap::new));
    }

    private Map<String, Spec> loadSpecMap(List<Budget> budgets, String specId) {
        boolean hasProjectBudget = budgets.stream()
                .anyMatch(budget -> "project".equals(normalize(budget.getBudgetLevel())));
        if (!hasProjectBudget || !StringUtils.hasText(specId)) {
            return Map.of();
        }
        Spec spec = specMapper.selectById(specId);
        return spec == null ? Map.of() : Map.of(specId, spec);
    }

    private Map<String, AiModel> loadModelMap(List<String> modelKeys) {
        if (CollectionUtils.isEmpty(modelKeys)) {
            return Map.of();
        }
        List<String> keys = modelKeys.stream().filter(StringUtils::hasText).distinct().toList();
        if (keys.isEmpty()) {
            return Map.of();
        }
        List<String> idKeys = keys.stream().filter(this::looksLikeUuid).toList();
        List<String> nameKeys = keys.stream().filter(key -> !looksLikeUuid(key)).toList();
        List<AiModel> models = new ArrayList<>();
        if (!idKeys.isEmpty()) {
            models.addAll(aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>().in(AiModel::getId, idKeys)));
        }
        if (!nameKeys.isEmpty()) {
            models.addAll(aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>().in(AiModel::getName, nameKeys)));
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

    private boolean isAlertThresholdHit(Budget budget, double usageRate) {
        if (usageRate >= 80D && Boolean.TRUE.equals(budget.getAlertThreshold80())) {
            return true;
        }
        return usageRate >= 50D && Boolean.TRUE.equals(budget.getAlertThreshold50());
    }

    private int priorityOf(BudgetAdmissionVO admission) {
        if (admission == null || admission.getStrategy() == null) {
            return 0;
        }
        return switch (admission.getStrategy()) {
            case STRATEGY_BLOCK -> 3;
            case STRATEGY_DOWNGRADE -> 2;
            case STRATEGY_ALERT -> 1;
            default -> 0;
        };
    }

    private BudgetAdmissionVO allowWithoutBudget() {
        BudgetAdmissionVO admission = new BudgetAdmissionVO();
        admission.setAllowed(Boolean.TRUE);
        admission.setStrategy(STRATEGY_ALLOW);
        admission.setUsageRate(0D);
        admission.setRemainingAmount(BigDecimal.ZERO);
        admission.setActionRequired(Boolean.FALSE);
        admission.setMessage("未配置活跃预算，允许执行。");
        return admission;
    }

    private String buildAuditDetail(BudgetAdmissionVO admission) {
        String traceId = UUID.randomUUID().toString();
        return "{"
                + "\"severity\":\"" + severityOf(admission) + "\","
                + "\"traceId\":\"" + traceId + "\","
                + "\"budgetId\":\"" + escape(admission.getBudgetId()) + "\","
                + "\"strategy\":\"" + escape(admission.getStrategy()) + "\","
                + "\"usageRate\":" + (admission.getUsageRate() == null ? 0D : admission.getUsageRate()) + ","
                + "\"remainingAmount\":\"" + admission.getRemainingAmount() + "\","
                + "\"message\":\"" + escape(admission.getMessage()) + "\""
                + "}";
    }

    private BudgetBlockEventVO toBlockEvent(AuditLog log) {
        BudgetBlockEventVO event = new BudgetBlockEventVO();
        event.setEventId(log.getId());
        event.setResourceType(log.getResource());
        event.setResourceId(log.getResourceId());
        event.setBudgetId(extractJsonString(log.getDetail(), "budgetId"));
        event.setStrategy(extractJsonString(log.getDetail(), "strategy"));
        event.setUsageRate(extractJsonDouble(log.getDetail(), "usageRate"));
        event.setRemainingAmount(extractJsonString(log.getDetail(), "remainingAmount"));
        event.setTraceId(extractJsonString(log.getDetail(), "traceId"));
        event.setSeverity(extractJsonString(log.getDetail(), "severity"));
        event.setMessage(extractJsonString(log.getDetail(), "message"));
        event.setOccurredAt(log.getCreatedAt());
        return event;
    }

    private String extractJsonString(String raw, String field) {
        if (!StringUtils.hasText(raw) || !StringUtils.hasText(field)) {
            return null;
        }
        Matcher matcher = Pattern.compile(String.format(JSON_STRING_FIELD_PATTERN.pattern(), Pattern.quote(field))).matcher(raw);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Double extractJsonDouble(String raw, String field) {
        if (!StringUtils.hasText(raw) || !StringUtils.hasText(field)) {
            return null;
        }
        Matcher matcher = Pattern.compile(String.format(JSON_NUMBER_FIELD_PATTERN.pattern(), Pattern.quote(field))).matcher(raw);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String severityOf(BudgetAdmissionVO admission) {
        if (STRATEGY_BLOCK.equals(admission.getStrategy())) {
            return "HIGH";
        }
        if (STRATEGY_DOWNGRADE.equals(admission.getStrategy())) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String normalizeOrDefault(String value, String defaultValue) {
        String normalized = normalize(value);
        return StringUtils.hasText(normalized) ? normalized : defaultValue;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase() : value;
    }

    private BigDecimal scaleMoney(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP);
    }

    private double roundTwoDecimal(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private boolean looksLikeUuid(String value) {
        return StringUtils.hasText(value)
                && value.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
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

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String requireTenantId() {
        return requireTenantId(SecurityUtil.getCurrentTenantId());
    }

    private String requireTenantId(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "租户上下文缺失");
        }
        return tenantId.trim();
    }

    private record TimeWindow(LocalDateTime start, LocalDateTime end) {
    }
}
