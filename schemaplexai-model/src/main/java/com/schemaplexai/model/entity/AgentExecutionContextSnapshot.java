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
 * Agent 执行上下文快照表实体
 */
@Data
@TableName(value = "sf_agent_execution_context_snapshot", autoResultMap = true)
public class AgentExecutionContextSnapshot implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    /** 关联执行 ID */
    private String executionId;

    /** 快照类型: initial=执行启动初始注入 / collected=执行中采集更新 */
    private String snapshotType;

    /** 四层上下文 JSON: {global, project, task, agent} */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> contextData;

    /** 上下文预估消耗 Token 数 */
    private Integer totalTokens;

    private LocalDateTime createdAt;
}
