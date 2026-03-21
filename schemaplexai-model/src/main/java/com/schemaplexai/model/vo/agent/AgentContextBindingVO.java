package com.schemaplexai.model.vo.agent;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Agent上下文绑定VO
 */
@Data
public class AgentContextBindingVO {

    private String id;
    private String agentId;
    private String contextId;
    private String contextName;
    private String sourceType;
    private Map<String, Object> sourceConfig;
    private String content;
    private String title;
    private String status;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
