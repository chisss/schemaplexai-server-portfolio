package com.schemaplexai.model.vo.quality;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class DeviationStatisticsVO {
    private Integer totalCount;
    private Integer openCount;
    private Integer resolvedCount;
    private Integer acknowledgedCount;
    private Integer ignoredCount;
    private Integer criticalCount;
    private Integer warningCount;
    private Integer infoCount;
    private List<Map<String, Object>> typeDistribution = new ArrayList<>();
    private List<Map<String, Object>> statusDistribution = new ArrayList<>();
    private List<Map<String, Object>> projectDistribution = new ArrayList<>();
}
