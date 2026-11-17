package com.schemaplexai.model.vo.cost;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 预算执行准入结果
 */
@Data
public class BudgetAdmissionVO {

    /** 是否允许进入执行链路 */
    private Boolean allowed;

    /** 准入策略: allow/alert/downgrade/block */
    private String strategy;

    /** 命中的预算 ID */
    private String budgetId;

    /** 预算使用率百分比 */
    private Double usageRate;

    /** 剩余金额 */
    private BigDecimal remainingAmount;

    /** 用户可读提示 */
    private String message;

    /** 是否需要人工关注或后续动作 */
    private Boolean actionRequired;

    /** 预算总额 */
    private BigDecimal budgetAmount;

    /** 已使用金额 */
    private BigDecimal usedAmount;
}
