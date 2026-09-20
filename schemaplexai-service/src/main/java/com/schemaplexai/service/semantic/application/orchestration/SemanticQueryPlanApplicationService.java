package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.service.semantic.domain.model.query.QueryIntent;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import com.schemaplexai.service.semantic.domain.service.QueryPlanCompilationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** QueryIntent 到参数化 QueryPlan 的应用编排；本阶段不执行计划。 */
@Service
@RequiredArgsConstructor
public class SemanticQueryPlanApplicationService {

    private final QueryPlanCompilationService compilationService;

    public QueryPlan compile(QueryIntent intent, String databaseType) {
        return compilationService.compile(intent, databaseType);
    }
}
