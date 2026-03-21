package com.schemaplexai.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 成本统计定时任务
 * 定期聚合Token消耗数据到ClickHouse
 */
@Slf4j
@Component
public class CostStatisticsTask {

    /**
     * 每小时执行一次成本聚合
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void aggregateCostData() {
        log.info("开始执行成本统计聚合任务...");
        // TODO: 实现成本数据聚合逻辑
        log.info("成本统计聚合任务完成");
    }
}
