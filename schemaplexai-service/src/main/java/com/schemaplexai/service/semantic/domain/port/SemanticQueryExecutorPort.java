package com.schemaplexai.service.semantic.domain.port;

import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionLimits;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryExplainResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;

/** 外部数据库/MCP 受控执行端口，不能接收原始 SQL。 */
public interface SemanticQueryExecutorPort {

    QueryExplainResult explain(QueryPlan plan, QueryExecutionLimits limits);

    QueryExecutionResult execute(QueryPlan plan, QueryExecutionLimits limits);
}
