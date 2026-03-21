package com.schemaplexai.model.converter;

import com.schemaplexai.common.enums.AgentStatusEnum;
import com.schemaplexai.model.dto.agent.AgentCreateRequest;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.vo.agent.AgentVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * Agent实体转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE,
        imports = {AgentStatusEnum.class})
public interface AgentConverter {

    /**
     * Agent -> AgentVO（configs/teamMembers/contextBindings 需要额外加载）
     */
    @Mapping(target = "configs", ignore = true)
    @Mapping(target = "teamMembers", ignore = true)
    @Mapping(target = "contextBindings", ignore = true)
    AgentVO toVO(Agent agent);

    List<AgentVO> toVOList(List<Agent> agents);

    /**
     * AgentCreateRequest -> Agent
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "status", expression = "java(AgentStatusEnum.INACTIVE.getCode())")
    @Mapping(target = "configCompleted", constant = "false")
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    Agent fromCreateRequest(AgentCreateRequest request);
}
