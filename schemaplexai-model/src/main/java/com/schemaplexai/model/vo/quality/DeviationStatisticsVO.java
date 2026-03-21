package com.schemaplexai.model.vo.quality;

import lombok.Data;

@Data
public class DeviationStatisticsVO {
    private Integer totalCount;
    private Integer openCount;
    private Integer resolvedCount;
    private Integer criticalCount;
    private Integer warningCount;
    private Integer infoCount;
}
