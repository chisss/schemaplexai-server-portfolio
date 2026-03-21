package com.schemaplexai.model.vo.agent;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent团队成员VO
 */
@Data
public class AgentTeamMemberVO {

    private String id;
    private String agentId;
    private String roleName;
    private String roleType;
    private Integer quantity;
    private String modelOverride;
    private String description;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
