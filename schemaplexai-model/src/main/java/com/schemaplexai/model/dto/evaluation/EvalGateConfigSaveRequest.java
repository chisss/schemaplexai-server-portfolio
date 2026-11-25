package com.schemaplexai.model.dto.evaluation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 保存评估门禁配置请求
 */
@Data
public class EvalGateConfigSaveRequest {

    @NotNull(message = "门禁开关不能为空")
    private Boolean enabled;

    @NotBlank(message = "请选择绑定的评估任务")
    private String taskId;

    @NotNull(message = "达标率门槛不能为空")
    @Min(value = 1, message = "达标率门槛最小为 1")
    @Max(value = 100, message = "达标率门槛最大为 100")
    private Integer passRateThreshold;
}
