package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 工具审批修正规则（渐进式信任）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_tool_approval_amendment", autoResultMap = true)
public class ToolApprovalAmendment extends BaseEntity {

    /** Agent ID（scope=AGENT时必填） */
    private String agentId;

    /** 工具编码 */
    private String toolCode;

    /** 命令匹配模式 */
    private String commandPattern;

    /** 匹配类型: PREFIX/EXACT/REGEX */
    private String matchType;

    /** 作用域: AGENT/TENANT */
    private String scope;

    /** 审批人ID */
    private String approvedBy;

    /** 过期时间 */
    private LocalDateTime expiresAt;

    /** 使用次数 */
    private Integer useCount;

    /** 最后使用时间 */
    private LocalDateTime lastUsedAt;
}
