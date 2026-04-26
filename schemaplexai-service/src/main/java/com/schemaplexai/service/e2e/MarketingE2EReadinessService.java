package com.schemaplexai.service.e2e;

import com.schemaplexai.model.vo.e2e.MarketingReadinessVO;

/**
 * 营销场景回归准备度服务
 */
public interface MarketingE2EReadinessService {

    MarketingReadinessVO checkMarketingScenarios(int from, int to);
}
