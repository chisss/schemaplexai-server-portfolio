package com.schemaplexai.service.cost;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.cost.BudgetCreateRequest;
import com.schemaplexai.model.dto.cost.BudgetQueryRequest;
import com.schemaplexai.model.dto.cost.BudgetUpdateRequest;
import com.schemaplexai.model.vo.cost.BudgetAlertVO;
import com.schemaplexai.model.vo.cost.BudgetUsageVO;
import com.schemaplexai.model.vo.cost.BudgetVO;

import java.util.List;

/**
 * 预算管理服务
 */
public interface BudgetService {

    BudgetVO create(BudgetCreateRequest request);

    PageResult<BudgetVO> page(BudgetQueryRequest request);

    BudgetVO update(String id, BudgetUpdateRequest request);

    BudgetVO upsertCurrentBudget(BudgetUpdateRequest request);

    void delete(String id);

    BudgetVO getCurrentBudget();

    BudgetUsageVO getUsage(String id);

    BudgetUsageVO getCurrentBudgetUsage();

    List<BudgetAlertVO> getAlerts(Integer page, Integer size);
}
