package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Agent 团队成员工具绑定实体
 */
@Data
@TableName(value = "sf_agent_team_member_tool_binding", autoResultMap = true)
public class AgentTeamMemberToolBinding implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户ID */
    @TableField(fill = FieldFill.INSERT)
    private String tenantId;

    /** 关联团队成员ID */
    private String memberId;

    /** 工具代码 */
    private String toolCode;

    /** 来源类型: builtin / skill / mcp */
    private String sourceType;

    /** 来源ID: MCP Server ID / Skill ID（builtin 时为空） */
    private String sourceRefId;

    /** 是否启用 */
    private Boolean enabled;

    /** 调用优先级 */
    private Integer priority;

    /** 工具参数覆盖配置 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> configOverride;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
