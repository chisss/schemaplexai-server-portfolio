package com.schemaplexai.model.vo.semantic;

import java.util.List;

/** EXPLAIN 结果，不包含业务数据和参数值。 */
public record SemanticQueryExplainVO(
        String planHash,
        String language,
        String statement,
        List<String> parameterNames,
        List<SemanticQueryPlanVO.ColumnVO> columns,
        List<SemanticQueryPlanVO.LineageVO> lineage,
        List<String> warnings) {
}
