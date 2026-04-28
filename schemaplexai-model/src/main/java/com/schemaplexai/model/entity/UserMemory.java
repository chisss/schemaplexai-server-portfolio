package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 用户长期记忆与偏好
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_user_memory", autoResultMap = true)
public class UserMemory extends BaseEntity {

    /** 所属用户ID */
    private String userId;

    /** Agent ID，可为空表示用户全局记忆 */
    private String agentId;

    /** 项目ID */
    private String projectId;

    /** 工作区ID */
    private String workspaceId;

    /** 会话ID */
    private String conversationId;

    /** 来源执行ID */
    private String executionId;

    /** 来源消息ID */
    private String sourceMessageId;

    /** 作用域: USER_GLOBAL/USER_AGENT/USER_PROJECT/USER_WORKSPACE */
    private String memoryScope;

    /** 类型: PROFILE_FACT/PREFERENCE/COMMUNICATION_STYLE/... */
    private String memoryKind;

    /** 来源: EXPLICIT/IMPLICIT/MANUAL/IMPORT/SYSTEM_MIGRATION */
    private String sourceType;

    /** 状态: CANDIDATE/ACTIVE/REJECTED/ARCHIVED */
    private String status;

    /** 记忆内容 */
    private String content;

    /** 结构化偏好值 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> structuredValue;

    /** 内容哈希 */
    private String contentHash;

    /** 置信度 */
    private BigDecimal confidenceScore;

    /** 重要度 */
    private BigDecimal importanceScore;

    /** 相关度 */
    private BigDecimal relevanceScore;

    /** 敏感级别 */
    private String sensitivityLevel;

    /** 是否置顶 */
    private Boolean pinned;

    /** 使用次数 */
    private Integer useCount;

    /** 最近使用时间 */
    private LocalDateTime lastUsedAt;

    /** 过期时间 */
    private LocalDateTime expiresAt;

    /** 被替代的目标ID */
    private String supersededBy;
}
