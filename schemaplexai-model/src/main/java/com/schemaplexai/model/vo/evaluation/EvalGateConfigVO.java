package com.schemaplexai.model.vo.evaluation;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评估门禁配置视图
 */
@Data
public class EvalGateConfigVO {

    /** 门禁开关 */
    private Boolean enabled;

    /** 绑定的评估任务 ID */
    private String taskId;

    /** 绑定任务名称（可空） */
    private String taskName;

    /** 绑定任务状态（可空） */
    private String taskStatus;

    /** 达标率门槛（1-100） */
    private Integer passRateThreshold;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;
}
