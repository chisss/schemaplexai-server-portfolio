package com.schemaplexai.service.cost.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.BudgetMapper;
import com.schemaplexai.model.converter.BudgetConverter;
import com.schemaplexai.model.dto.cost.BudgetCreateRequest;
import com.schemaplexai.model.dto.cost.BudgetQueryRequest;
import com.schemaplexai.model.dto.cost.BudgetUpdateRequest;
import com.schemaplexai.model.entity.Budget;
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
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BudgetVO create(BudgetCreateRequest request) {
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
            updateEntity.setOverLimitStrategy(request.getOverLimitStrategy());
        }
        if (StringUtils.hasText(request.getStatus())) {
            updateEntity.setStatus(request.getStatus());
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
        var budget = entityValidator.requireExists(budgetMapper, id, ResultCode.BUDGET_NOT_FOUND);

        // TODO: 从ClickHouse查询当前周期的实际消耗金额
        var usedAmount = BigDecimal.ZERO;
        var remainAmount = budget.getBudgetAmount().subtract(usedAmount);
        var usageRate = 0.0;

        var vo = new BudgetUsageVO();
        vo.setBudgetAmount(budget.getBudgetAmount());
        vo.setUsedAmount(usedAmount);
        vo.setRemainAmount(remainAmount);
        vo.setUsageRate(usageRate);
        vo.setAlertLevel("normal");
        // TODO: 根据预算周期（daily/monthly/quarterly）计算当前周期的起止时间
        vo.setCycleStart(null);
        vo.setCycleEnd(null);

        return vo;
    }

    @Override
    public List<BudgetAlertVO> getAlerts(Integer page, Integer size) {
        log.info("查询预算告警: page={}, size={}", page, size);

        // TODO: 从预算告警机制查询告警记录（需要ClickHouse消耗数据 + 预算阈值对比）
        return Collections.emptyList();
    }
}
