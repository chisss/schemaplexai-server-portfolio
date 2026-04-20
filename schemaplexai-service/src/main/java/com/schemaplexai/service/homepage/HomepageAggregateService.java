package com.schemaplexai.service.homepage;

import com.schemaplexai.model.vo.homepage.HomepageAggregateVO;

/**
 * 首页聚合服务
 */
public interface HomepageAggregateService {

    /**
     * 获取首页聚合数据
     *
     * @param mode 模式：tasks / ai / situation，为空返回全部
     * @return 聚合数据
     */
    HomepageAggregateVO aggregate(String mode);
}
