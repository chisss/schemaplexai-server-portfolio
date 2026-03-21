package com.schemaplexai.model.converter;

import com.schemaplexai.model.entity.AgentContextBinding;
import com.schemaplexai.model.vo.agent.AgentContextBindingVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * Agent上下文绑定转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface AgentContextBindingConverter {

    AgentContextBindingVO toVO(AgentContextBinding binding);

    List<AgentContextBindingVO> toVOList(List<AgentContextBinding> bindings);
}
