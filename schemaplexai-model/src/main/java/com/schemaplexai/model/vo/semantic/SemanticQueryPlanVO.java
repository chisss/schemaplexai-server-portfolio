package com.schemaplexai.model.vo.semantic;

import java.util.List;

/** 服务端生成的只读语义查询计划预览。 */
public record SemanticQueryPlanVO(
        String status,
        String question,
        String semanticVersionId,
        String sourceId,
        String databaseType,
        String planHash,
        String language,
        String statement,
        List<String> parameterNames,
        List<ColumnVO> columns,
        List<LineageVO> lineage,
        List<String> warnings,
        List<SemanticQueryInterpretVO.ClarificationVO> clarifications,
        List<String> reasons) {

    public record ColumnVO(String role, String alias, String expression, String semanticIri) {
    }

    public record LineageVO(String semanticIri, String sourceId, String physicalObject, String physicalField) {
    }
}
