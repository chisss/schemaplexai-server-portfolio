package com.schemaplexai.model.vo.workflow;

import lombok.Data;

/**
 * 工作流模板统计VO
 */
@Data
public class WorkflowTemplateStatsVO {

    /** 活跃工作流数量（status=published） */
    private Long activeWorkflows;

    /** 平均成功率 */
    private Double avgSuccessRate;

    /** 24小时内运行总数 */
    private Long totalRuns24h;
}
