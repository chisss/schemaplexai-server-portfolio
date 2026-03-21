package com.schemaplexai.service.cost.impl;

import com.schemaplexai.model.vo.cost.CostByDimensionVO;
import com.schemaplexai.model.vo.cost.CostOverviewVO;
import com.schemaplexai.model.vo.cost.CostTrendVO;
import com.schemaplexai.service.cost.CostAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 成本分析服务实现
 * 数据源依赖 ClickHouse，当前所有方法返回占位默认值
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostAnalysisServiceImpl implements CostAnalysisService {

    @Override
    public CostOverviewVO overview(String timeRange) {
        log.info("查询成本概览: timeRange={}", timeRange);

        // TODO: 从ClickHouse查询成本概览数据（sf_cost_record + mv_daily_cost）
        var vo = new CostOverviewVO();
        vo.setTotalCost(BigDecimal.ZERO);
        vo.setTotalTokens(0L);
        vo.setTodayCost(BigDecimal.ZERO);
        vo.setTodayTokens(0L);
        vo.setCostChangeRate(0.0);
        vo.setBudgetUsageRate(0.0);
        vo.setCostByModel(new ArrayList<>());
        return vo;
    }

    @Override
    public CostTrendVO trend(String timeRange, String startTime, String endTime, String groupBy) {
        log.info("查询成本趋势: timeRange={}, startTime={}, endTime={}, groupBy={}",
                timeRange, startTime, endTime, groupBy);

        // TODO: 从ClickHouse查询成本趋势数据（按groupBy维度聚合）
        var vo = new CostTrendVO();
        vo.setCostTrend(new ArrayList<>());
        vo.setTokenTrend(new ArrayList<>());
        return vo;
    }

    @Override
    public List<CostByDimensionVO> byProject(String timeRange, String startTime, String endTime) {
        log.info("查询按项目维度成本: timeRange={}, startTime={}, endTime={}", timeRange, startTime, endTime);

        // TODO: 从ClickHouse查询按项目维度的成本分布数据
        return Collections.emptyList();
    }

    @Override
    public List<CostByDimensionVO> byModel(String timeRange, String startTime, String endTime) {
        log.info("查询按模型维度成本: timeRange={}, startTime={}, endTime={}", timeRange, startTime, endTime);

        // TODO: 从ClickHouse查询按模型维度的成本分布数据
        return Collections.emptyList();
    }

    @Override
    public List<CostByDimensionVO> byAgent(String timeRange, String startTime, String endTime) {
        log.info("查询按Agent维度成本: timeRange={}, startTime={}, endTime={}", timeRange, startTime, endTime);

        // TODO: 从ClickHouse查询按Agent维度的成本分布数据
        return Collections.emptyList();
    }

    @Override
    public List<CostByDimensionVO> byUser(String timeRange, String startTime, String endTime) {
        log.info("查询按用户维度成本: timeRange={}, startTime={}, endTime={}", timeRange, startTime, endTime);

        // TODO: 从ClickHouse查询按用户维度的成本分布数据
        return Collections.emptyList();
    }
}
