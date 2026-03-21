package com.schemaplexai.model.dto.cicd;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 更新CICD Pipeline请求
 */
@Data
public class CicdPipelineUpdateRequest {

    /** Pipeline名称 */
    @Size(max = 200, message = "名称不能超过200个字符")
    private String name;

    /** Pipeline配置 */
    private Map<String, Object> config;

    /** 触发规则 */
    private Map<String, Object> triggerRules;

    /** 状态: active/inactive */
    private String status;

    /** 描述 */
    private String description;
}
