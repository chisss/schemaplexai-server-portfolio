package com.schemaplexai.model.vo.spec;

import java.time.LocalDateTime;

/**
 * Spec版本快照VO
 */
public record SpecVersionVO(
        /** 版本ID */
        String id,
        /** 文档类型 */
        String docType,
        /** 版本内容快照 */
        String content,
        /** 版本号 */
        Integer versionNumber,
        /** 变更说明 */
        String changeSummary,
        /** 关联工作流节点ID */
        String workflowNodeId,
        /** 创建人 */
        String createdBy,
        /** 创建时间 */
        LocalDateTime createdAt
) {}
