package com.schemaplexai.service.cost;

import com.schemaplexai.model.vo.cost.CostByDimensionVO;
import com.schemaplexai.model.vo.cost.CostOverviewVO;
import com.schemaplexai.model.vo.cost.CostTrendVO;

import java.util.List;

/**
 * 成本分析服务
 */
public interface CostAnalysisService {

    CostOverviewVO overview(String timeRange);

    CostTrendVO trend(String timeRange, String startTime, String endTime, String groupBy);

    List<CostByDimensionVO> byProject(String timeRange, String startTime, String endTime);

    List<CostByDimensionVO> byModel(String timeRange, String startTime, String endTime);

    List<CostByDimensionVO> byAgent(String timeRange, String startTime, String endTime);

    List<CostByDimensionVO> byUser(String timeRange, String startTime, String endTime);
}
