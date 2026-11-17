package com.schemaplexai.service.cost;

import com.schemaplexai.model.vo.cost.BudgetAdmissionVO;
import com.schemaplexai.model.vo.cost.BudgetBlockEventVO;

import java.util.List;

/**
 * 预算执行准入服务
 */
public interface BudgetAdmissionService {

    BudgetAdmissionVO evaluateForAgentExecution(String agentId, String specId, String userId, String model);

    BudgetAdmissionVO evaluateForTenantAgentExecution(String tenantId, String agentId, String specId, String userId, String model);

    BudgetAdmissionVO getCurrentAdmissionStatus();

    void recordAdmissionAudit(BudgetAdmissionVO admission, String resourceType, String resourceId);

    void recordAdmissionAudit(String tenantId, BudgetAdmissionVO admission, String resourceType, String resourceId);

    List<BudgetBlockEventVO> listRecentBlockEvents(Integer limit);
}
