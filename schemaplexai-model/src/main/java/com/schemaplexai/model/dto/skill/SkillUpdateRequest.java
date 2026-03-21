package com.schemaplexai.model.dto.skill;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class SkillUpdateRequest {
    private String id;
    /**
     * 技能名称（唯一标识）
     */
    @Size(max = 100, message = "技能名称不能超过100个字符")
    private String name;
    /**
     * 显示名称
     */
    @Size(max = 100, message = "显示名称不能超过100个字符")
    private String displayName;
    /**
     * 描述
     */
    private String description;
    /**
     * 版本
     */
    private String version;
    /**
     * 分类: builtin/custom/mcp
     */
    private String category;
    /**
     * 参数定义
     */
    private List<Object> parameters;
    /**
     * 实现方式 {type: script/mcp/api, content: ...}
     */
    private Map<String, Object> implementation;

    /**
     * 状态: active/inactive
     */
    private String status;
}