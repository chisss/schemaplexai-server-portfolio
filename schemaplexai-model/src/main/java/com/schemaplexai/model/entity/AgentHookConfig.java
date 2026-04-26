package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Agent Hook 配置表实体
 */
@Data
@TableName("sf_agent_hook_config")
public class AgentHookConfig implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    private String agentId;

    /** 对应 AgentHook.name() */
    private String hookCode;

    /** BEFORE_MODEL_CALL / AFTER_MODEL_CALL / BEFORE_TOOL_EXECUTE / AFTER_TOOL_EXECUTE / ON_LOOP_COMPLETE */
    private String hookType;

    private Boolean enabled;

    /** 越小越先执行 */
    private Integer priority;

    /** Hook 自定义参数 */
    private String configJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
