package com.schemaplexai.service.integration.cicd;

import java.util.Map;

/**
 * CICD Pipeline 触发器接口
 * 各平台（Jenkins、GitLab CI、GitHub Actions）实现此接口
 */
public interface CicdTrigger {

    /**
     * 获取支持的 Pipeline 类型
     */
    String getPipelineType();

    /**
     * 触发 Pipeline 构建
     *
     * @param config Pipeline 配置（包含 API 地址、凭证、项目信息等）
     * @return 触发结果（包含 build_id、url 等）
     */
    Map<String, Object> trigger(Map<String, Object> config) throws Exception;
}
