package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Team 成员上下文绑定实体
 */
@Data
@TableName(value = "sf_agent_team_member_context_binding", autoResultMap = true)
public class AgentTeamMemberContextBinding implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String memberId;

    private String contextId;

    private String sourceType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> sourceConfig;

    private String content;

    private String title;

    private String status;

    private Integer sortOrder;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
