package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 评估门禁配置（后台管理页维护，单租户单行）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sf_eval_gate_config")
public class EvalGateConfig extends BaseEntity {

    /**
     * 门禁开关：true=启用 CI 门禁
     */
    private Boolean enabled;

    /**
     * 绑定的评估任务 ID（门禁判定对象）
     */
    private String taskId;

    /**
     * 达标率门槛（1-100，100=严格全过门禁）
     */
    private Integer passRateThreshold;
}
