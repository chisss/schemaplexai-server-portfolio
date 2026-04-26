package com.schemaplexai.service.monitor;

import com.schemaplexai.model.vo.system.RouteAnalysisVO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 路由分析服务
 */
public interface RouteAnalysisService {

    void recordDecision(String tenantId,
                        String agentId,
                        String requestId,
                        String selectedModelId,
                        String strategy,
                        List<String> candidateModels,
                        String reason,
                        long latencyMs,
                        long tokenCount,
                        BigDecimal cost);

    RouteAnalysisVO getAnalysis();
}
