package com.schemaplexai.model.dto.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * Agent上下文绑定请求DTO
 */
@Data
public class AgentContextBindingDTO {

    /** 关联上下文ID（source_type=context_module时必填） */
    private String contextId;

    /** 来源类型: manual/context_module/gitlab */
    @NotBlank(message = "来源类型不能为空")
    private String sourceType;

    /** 来源配置（GitLab时填仓库URL、分支等） */
    private Map<String, Object> sourceConfig;

    /** 手动输入的上下文内容 */
    private String content;

    /** 条目标题 */
    private String title;

    /** 排序序号 */
    private Integer sortOrder;
}
