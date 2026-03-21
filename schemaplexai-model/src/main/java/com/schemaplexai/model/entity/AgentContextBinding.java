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
 * Agent上下文绑定表实体
 */
@Data
@TableName(value = "sf_agent_context_binding", autoResultMap = true)
public class AgentContextBinding implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 关联AgentID */
    private String agentId;

    /** 关联上下文ID */
    private String contextId;

    /** 来源类型: manual/context_module/gitlab */
    private String sourceType;

    /** 来源配置JSON */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> sourceConfig;

    /** 手动输入的上下文内容 */
    private String content;

    /** 条目标题 */
    private String title;

    /** 状态: active/inactive */
    private String status;

    /** 排序序号 */
    private Integer sortOrder;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
