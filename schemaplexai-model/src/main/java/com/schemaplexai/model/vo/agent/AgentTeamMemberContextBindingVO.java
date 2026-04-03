package com.schemaplexai.model.vo.agent;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Team 成员上下文绑定 VO
 */
@Data
public class AgentTeamMemberContextBindingVO {

    private String id;
    private String memberId;
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
