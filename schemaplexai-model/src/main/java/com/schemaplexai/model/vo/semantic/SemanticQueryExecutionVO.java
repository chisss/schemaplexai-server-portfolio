package com.schemaplexai.model.vo.semantic;

import java.util.List;
import java.util.Map;

/** 受控只读查询结果和物理血缘。 */
public record SemanticQueryExecutionVO(
        String planHash,
        List<Map<String, Object>> rows,
        List<SemanticQueryPlanVO.ColumnVO> columns,
        List<SemanticQueryPlanVO.LineageVO> lineage,
        int rowCount,
        boolean truncated,
        long elapsedMs,
        String auditId,
        List<String> warnings) {
}
