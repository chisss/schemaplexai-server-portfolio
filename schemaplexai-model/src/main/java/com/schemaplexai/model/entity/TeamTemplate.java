package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 团队模板表实体
 */
@Data
@TableName(value = "sf_team_template", autoResultMap = true)
public class TeamTemplate implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户ID */
    private String tenantId;

    /** 模板名称 */
    private String name;

    /** 模板唯一编码 */
    private String code;

    /** 描述 */
    private String description;

    /** 模板分类 */
    private String category;

    /** 图标标识 */
    private String icon;

    /** 推荐角色配置JSON数组 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> recommendedRoles;

    /** 状态: active/inactive */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
