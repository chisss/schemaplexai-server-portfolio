package com.schemaplexai.model.vo.monitor;

import lombok.Data;

/**
 * 仪表盘概览VO
 */
@Data
public class DashboardVO {
    private Integer activeAgentCount;
    private Integer taskQueueDepth;
    private Double taskSuccessRate;
    private Long avgExecutionTimeMs;
    private Long todayTokenConsumption;
    private Integer deviationAlertCount;
    private Integer pendingApprovalCount;
    private SystemHealthVO systemHealth;
}
