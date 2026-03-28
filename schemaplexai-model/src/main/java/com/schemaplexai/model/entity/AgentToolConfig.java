package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
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
 * Agent 工具配置实例实体
 * 存储每个工具绑定的具体配置值
 */
@Data
@TableName(value = "sf_agent_tool_config", autoResultMap = true)
public class AgentToolConfig implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String bindingId;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> configValue;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
