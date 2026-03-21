package com.schemaplexai.model.dto.skill;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 创建技能请求
 */
@Data
public class SkillCreateRequest {

    /** 技能名称（唯一标识） */
    @NotBlank(message = "技能名称不能为空")
    @Size(max = 100, message = "技能名称不能超过100个字符")
    private String name;

    /** 显示名称 */
    @Size(max = 100, message = "显示名称不能超过100个字符")
    private String displayName;

    /** 描述 */
    private String description;

    /** 版本 */
    private String version;

    /** 分类: builtin/custom/mcp */
    @NotBlank(message = "技能分类不能为空")
    private String category;

    /** 参数定义 */
    private List<Object> parameters;

    /** 实现方式 {type: script/mcp/api, content: ...} */
    private Map<String, Object> implementation;
}
