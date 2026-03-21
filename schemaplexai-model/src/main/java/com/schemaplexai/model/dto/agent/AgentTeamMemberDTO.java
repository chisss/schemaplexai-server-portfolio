package com.schemaplexai.model.dto.agent;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Agent团队成员请求DTO
 */
@Data
public class AgentTeamMemberDTO {

    /** 成员ID（更新时使用） */
    private String id;

    /** 角色显示名称 */
    @NotBlank(message = "角色名称不能为空")
    private String roleName;

    /** 角色类型编码 */
    @NotBlank(message = "角色类型不能为空")
    private String roleType;

    /** 数量 */
    @Min(value = 1, message = "数量至少为1")
    private Integer quantity;

    /** 模型覆盖 */
    private String modelOverride;

    /** 描述 */
    private String description;

    /** 排序序号 */
    private Integer sortOrder;
}
