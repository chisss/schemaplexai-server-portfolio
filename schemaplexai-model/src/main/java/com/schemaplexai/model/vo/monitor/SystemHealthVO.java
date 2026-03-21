package com.schemaplexai.model.vo.monitor;

import lombok.Data;
import java.util.Map;

/**
 * 系统健康状态VO
 */
@Data
public class SystemHealthVO {
    /** 状态: healthy/degraded/unhealthy */
    private String status;
    private Double cpuUsage;
    private Double memoryUsage;
    private Double diskUsage;
    /** 服务状态: {postgresql: up, redis: up, rabbitmq: up, clickhouse: up} */
    private Map<String, String> serviceStatus;
}
