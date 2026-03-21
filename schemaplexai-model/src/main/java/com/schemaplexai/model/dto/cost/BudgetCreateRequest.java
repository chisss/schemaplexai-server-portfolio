package com.schemaplexai.model.dto.cost;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;

/**
 * 预算创建请求
 */
@Data
public class BudgetCreateRequest {
    @NotBlank(message = "预算级别不能为空")
    private String budgetLevel;
    @NotBlank(message = "关联对象ID不能为空")
    private String targetId;
    @NotBlank(message = "预算周期不能为空")
    private String budgetCycle;
    @NotNull(message = "预算金额不能为空")
    private BigDecimal budgetAmount;
    private Boolean alertThreshold50 = true;
    private Boolean alertThreshold80 = true;
    private Boolean alertThreshold100 = true;
    private String overLimitStrategy = "alert";
}
