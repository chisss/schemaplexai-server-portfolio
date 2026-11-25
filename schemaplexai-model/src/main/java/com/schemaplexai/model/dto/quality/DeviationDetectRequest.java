package com.schemaplexai.model.dto.quality;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.List;

@Data
public class DeviationDetectRequest {
    /** 可选，独立的 Agent 执行可能没有关联 Spec。 */
    private String specId;
    @NotBlank(message = "Agent执行ID不能为空")
    private String agentExecutionId;
    /** 偏离类型过滤，不传则全量检测 */
    private List<String> deviationTypes;
}
