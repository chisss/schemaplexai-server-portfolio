package com.schemaplexai.model.vo.agent;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Agent 团队成员工具绑定 VO
 */
@Data
public class AgentTeamMemberToolBindingVO {

    private String id;
    private String memberId;
    private String toolCode;
    private String sourceType;
    private String sourceRefId;
    private Boolean enabled;
    private Integer priority;
    private Map<String, Object> configOverride;
    private LocalDateTime createdAt;
}
