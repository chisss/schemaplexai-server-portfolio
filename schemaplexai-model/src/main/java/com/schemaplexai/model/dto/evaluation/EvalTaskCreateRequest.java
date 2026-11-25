package com.schemaplexai.model.dto.evaluation;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 创建评估任务请求
 */
@Data
public class EvalTaskCreateRequest {

    /**
     * 评估类型：QA=单轮问答（默认），TRAJECTORY=Agent 执行轨迹
     */
    private String taskType = "QA";

    private String name;

    @NotBlank(message = "数据集不能为空")
    private String datasetId;

    /**
     * QA 模式：参与评估的模型列表
     */
    private List<String> modelIds;

    /**
     * TRAJECTORY 模式：被测 Agent ID 列表
     */
    private List<String> agentIds;

    /**
     * LLM-as-Judge 模型 ID 列表（空 = 退化纯规则分）
     */
    private List<String> judgeModelIds;

    /**
     * 模式相关目标列表校验：QA 需模型，TRAJECTORY 需 Agent + Judge 模型
     */
    @AssertTrue(message = "QA 模式至少选择一个模型；TRAJECTORY 模式至少选择一个 Agent 与一个 Judge 模型")
    public boolean isTargetListValid() {
        if ("TRAJECTORY".equalsIgnoreCase(taskType)) {
            return agentIds != null && !agentIds.isEmpty()
                    && judgeModelIds != null && !judgeModelIds.isEmpty();
        }
        return modelIds != null && !modelIds.isEmpty();
    }
}
