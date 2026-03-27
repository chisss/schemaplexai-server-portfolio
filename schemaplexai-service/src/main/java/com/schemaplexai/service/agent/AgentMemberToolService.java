package com.schemaplexai.service.agent;

import com.schemaplexai.model.dto.agent.AgentTeamMemberToolBindingDTO;
import com.schemaplexai.model.vo.agent.AgentTeamMemberToolBindingVO;

import java.util.List;

/**
 * Agent 团队成员工具绑定服务接口
 */
public interface AgentMemberToolService {

    /**
     * 获取团队成员的工具绑定列表
     */
    List<AgentTeamMemberToolBindingVO> getMemberToolBindings(String memberId);

    /**
     * 批量保存团队成员的工具绑定（全量覆盖）
     */
    List<AgentTeamMemberToolBindingVO> saveMemberToolBindings(String memberId, List<AgentTeamMemberToolBindingDTO> request);

    /**
     * 删除团队成员的工具绑定
     */
    void deleteMemberToolBinding(String bindingId);
}
