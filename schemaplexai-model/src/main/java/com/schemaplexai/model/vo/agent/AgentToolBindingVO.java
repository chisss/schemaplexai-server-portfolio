package com.schemaplexai.model.vo.agent;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Agent 工具绑定 VO
 */
@Data
public class AgentToolBindingVO {

    private String id;
    private String agentId;
    private String toolCode;
    private String sourceType;
    private String sourceRefId;
    private Boolean enabled;
    private Integer priority;
    private Map<String, Object> configOverride;
    private LocalDateTime createdAt;
}
