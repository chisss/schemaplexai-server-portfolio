package com.schemaplexai.model.vo.e2e;

import lombok.Data;

import java.util.List;

/**
 * 营销场景回归准备度
 */
@Data
public class MarketingReadinessVO {

    private Integer scenarioCount;

    private Integer readyCount;

    private Integer blockingCount;

    private List<MarketingScenarioReadinessItemVO> items;
}
