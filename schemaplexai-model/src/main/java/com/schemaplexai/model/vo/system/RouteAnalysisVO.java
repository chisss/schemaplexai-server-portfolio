package com.schemaplexai.model.vo.system;

import lombok.Data;

import java.util.List;

/**
 * 路由分析视图
 */
@Data
public class RouteAnalysisVO {

    private List<ModelRuntimeMetricVO> realtimeMetrics;

    private List<RouteAnalysisModelShareVO> selectedDistribution;

    private List<RouteAnalysisTrendPointVO> trend;

    private List<RoutingDecisionLogVO> recentLogs;
}
