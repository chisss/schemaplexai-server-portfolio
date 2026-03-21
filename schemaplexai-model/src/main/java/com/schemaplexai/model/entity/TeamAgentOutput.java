package com.schemaplexai.model.entity;

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
 * Team Agent 各 Sub-Agent 产出持久化记录
 */
@Data
@TableName(value = "sf_team_agent_output", autoResultMap = true)
public class TeamAgentOutput implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    /** Team Agent ID */
    private String teamAgentId;

    /** Team 整体执行 ID */
    private String teamExecutionId;

    /** Sub-Agent ID */
    private String subAgentId;

    /** Agent 角色: lead/sub */
    private String role;

    /** 产出类型: summary/full/artifact */
    private String outputType;

    /** 产出文本内容 */
    private String outputContent;

    /** 产出元数据（文件列表/MR URL 等） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> outputMetadata;

    private LocalDateTime completedAt;

    @TableLogic
    private Integer deleted;
}
