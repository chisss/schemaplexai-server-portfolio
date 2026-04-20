package com.schemaplexai.model.vo.homepage;

import lombok.Data;

import java.util.List;

/**
 * 首页聚合响应
 */
@Data
public class HomepageAggregateVO {

    /** 情境摘要 */
    private ContextSummaryVO contextSummary;

    /** 推荐动作 */
    private List<RecommendedActionVO> actions;

    /** 左栏：任务面板数据 */
    private TaskPanelVO taskPanel;

    /** 右栏：态势面板数据 */
    private SituationPanelVO situationPanel;
}
