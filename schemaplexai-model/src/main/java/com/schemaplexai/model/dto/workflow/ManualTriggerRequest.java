package com.schemaplexai.model.dto.workflow;

import lombok.Data;

import java.util.Map;

/**
 * 手动触发工作流请求
 */
@Data
public class ManualTriggerRequest {

    /** 实例名称（可选，为空时自动生成） */
    private String name;

    /** 运行时入参 */
    private Map<String, Object> variables;
}
