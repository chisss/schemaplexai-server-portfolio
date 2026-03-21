package com.schemaplexai.model.dto.agent;

import lombok.Data;

import java.util.List;

/**
 * Agent团队成员批量保存请求
 */
@Data
public class AgentTeamMemberBatchRequest {

    /** 工作类型/团队模板code */
    private String workType;

    /** 团队成员列表 */
    private List<AgentTeamMemberDTO> members;
}
