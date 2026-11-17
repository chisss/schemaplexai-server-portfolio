package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 执行中用户引导（Steering）：Agent 执行过程中用户补充的最新引导信息。
 * 状态：pending（待注入）/ applied（已注入下一轮）/ discarded（已丢弃）。
 * 此前任务与上下文不丢失，后续执行以最新引导为最高优先级。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sf_execution_steering")
public class ExecutionSteering extends BaseEntity {

    /** 关联执行 ID */
    private String executionId;

    /** 引导序号（同执行内单调递增，恢复/重入时按序应用） */
    private Integer seq;

    /** 引导内容 */
    private String content;

    /** 状态: pending/applied/discarded */
    private String status;

    /** 应用时间 */
    private LocalDateTime appliedAt;
}
