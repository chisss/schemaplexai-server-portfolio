package com.schemaplexai.service.ai;

import com.schemaplexai.model.vo.system.AiModelRealHealthCheckVO;

/**
 * AI 模型真实协议健康检查服务
 */
public interface AiModelRealHealthCheckService {

    AiModelRealHealthCheckVO testRealConnectivity(String modelId);
}
