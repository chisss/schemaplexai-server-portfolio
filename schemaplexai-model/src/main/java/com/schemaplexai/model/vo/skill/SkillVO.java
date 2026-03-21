package com.schemaplexai.model.vo.skill;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 技能视图对象
 */
@Data
public class SkillVO {

    /** 主键ID */
    private String id;

    /** 技能名称 */
    private String name;

    /** 显示名称 */
    private String displayName;

    /** 描述 */
    private String description;

    /** 版本 */
    private String version;

    /** 分类: builtin/custom/mcp */
    private String category;

    /** 参数定义 */
    private List<Object> parameters;

    /** 实现方式 */
    private Map<String, Object> implementation;

    /** 状态 */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 创建人名称 */
    private String createdByName;
}
