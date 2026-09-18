package com.schemaplexai.model.vo.semantic;

import java.time.LocalDateTime;
import java.util.List;

/** 语义版本校验结果。 */
public record SemanticValidationVO(
        boolean valid,
        String versionId,
        long revision,
        List<Issue> issues,
        LocalDateTime validatedAt) {

    /** 可定位到本体节点的校验问题。 */
    public record Issue(
            String code,
            String severity,
            String message,
            String nodeId,
            String semanticIri,
            String path) {
    }
}
