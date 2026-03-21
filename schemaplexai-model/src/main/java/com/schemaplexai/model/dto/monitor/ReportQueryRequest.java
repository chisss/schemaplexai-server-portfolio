package com.schemaplexai.model.dto.monitor;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 报表查询请求
 */
@Data
public class ReportQueryRequest {
    /** 时间范围: last_24h/last_7d/last_30d/custom */
    private String timeRange = "last_7d";
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 分组方式: hour/day/week/month */
    private String groupBy = "day";
    /** 逗号分隔的项目ID */
    private String projectIds;
}
