package com.schemaplexai.service.cost.validator;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.BudgetMapper;
import com.schemaplexai.model.entity.Budget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 预算业务校验器 — 封装预算相关的业务规则校验
 */
@Component
@RequiredArgsConstructor
public class BudgetValidator {

    private final BudgetMapper budgetMapper;

    /**
     * 校验预算周期唯一性
     * 同一（budgetLevel, targetId, budgetCycle）下不允许存在多个活跃预算
     *
     * @param budgetLevel 预算级别
     * @param targetId    关联对象ID
     * @param budgetCycle 预算周期
     */
    public void validateCycleUnique(String budgetLevel, String targetId, String budgetCycle) {
        var count = budgetMapper.selectCount(
                new LambdaQueryWrapper<Budget>()
                        .eq(Budget::getBudgetLevel, budgetLevel)
                        .eq(Budget::getTargetId, targetId)
                        .eq(Budget::getBudgetCycle, budgetCycle)
                        .eq(Budget::getStatus, CommonConstant.STATUS_ACTIVE)
        );
        if (count > 0) {
            throw new BusinessException(ResultCode.BUDGET_CYCLE_CONFLICT);
        }
    }

    /**
     * 校验预算金额合法性
     * 金额必须大于零
     *
     * @param amount 预算金额
     */
    public void validateAmountPositive(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ResultCode.BUDGET_AMOUNT_INVALID);
        }
    }
}
