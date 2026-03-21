package com.schemaplexai.model.vo.context;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上下文关联关系VO
 */
@Data
public class ContextRelationVO {

    private String id;

    /** 来源上下文ID */
    private String fromContextId;

    /** 目标上下文ID */
    private String toContextId;

    /** 来源上下文名称（冗余，方便前端展示） */
    private String fromContextName;

    /** 目标上下文名称（冗余，方便前端展示） */
    private String toContextName;

    /** 关联类型: depends_on/extends/references/belongs_to */
    private String relationType;

    /** 关联描述 */
    private String description;

    private LocalDateTime createdAt;
}
