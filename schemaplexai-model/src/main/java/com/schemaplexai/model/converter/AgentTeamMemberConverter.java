package com.schemaplexai.model.converter;

import com.schemaplexai.model.entity.AgentTeamMember;
import com.schemaplexai.model.vo.agent.AgentTeamMemberVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * Agent团队成员转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface AgentTeamMemberConverter {

    AgentTeamMemberVO toVO(AgentTeamMember member);

    List<AgentTeamMemberVO> toVOList(List<AgentTeamMember> members);
}
