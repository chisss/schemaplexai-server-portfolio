package com.schemaplexai.service.agent;

import com.schemaplexai.model.dto.agent.AgentTeamMemberContextBindingDTO;
import com.schemaplexai.model.vo.agent.AgentTeamMemberContextBindingVO;

import java.util.List;

/**
 * Team 成员上下文绑定服务
 */
public interface AgentMemberContextService {

    /**
     * 获取成员上下文绑定
     */
    List<AgentTeamMemberContextBindingVO> getMemberContextBindings(String memberId);

    /**
     * 保存成员上下文绑定（全量覆盖）
     */
    List<AgentTeamMemberContextBindingVO> saveMemberContextBindings(String memberId, List<AgentTeamMemberContextBindingDTO> request);

    /**
     * 删除成员上下文绑定
     */
    void deleteMemberContextBinding(String bindingId);
}
