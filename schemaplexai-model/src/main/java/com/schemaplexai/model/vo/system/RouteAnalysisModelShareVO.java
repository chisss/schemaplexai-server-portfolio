package com.schemaplexai.model.vo.system;

import lombok.Data;

/**
 * 路由流量占比视图
 */
@Data
public class RouteAnalysisModelShareVO {

    private String modelId;

    private String modelName;

    private Long requestCount;

    private Double trafficShare;
}
