package com.schemaplexai.model.converter;

import com.schemaplexai.model.entity.AgentConfig;
import com.schemaplexai.model.vo.agent.AgentConfigVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * Agent配置转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface AgentConfigConverter {

    AgentConfigVO toVO(AgentConfig config);

    List<AgentConfigVO> toVOList(List<AgentConfig> configs);
}
