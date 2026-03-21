package com.schemaplexai.model.vo.spec;

import java.time.LocalDateTime;

/**
 * Spec文档VO
 */
public record SpecDocumentVO(
        /** 文档ID */
        String id,
        /** 文档类型: requirements/design/tasks */
        String docType,
        /** 文档内容（Markdown） */
        String content,
        /** 当前版本号 */
        Integer version,
        /** 创建人 */
        String createdBy,
        /** 更新时间 */
        LocalDateTime updatedAt
) {}
