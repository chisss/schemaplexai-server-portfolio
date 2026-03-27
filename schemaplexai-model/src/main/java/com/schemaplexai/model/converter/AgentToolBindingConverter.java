package com.schemaplexai.model.converter;

import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.vo.agent.AgentToolBindingVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * Agent 工具绑定转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface AgentToolBindingConverter {

    AgentToolBindingVO toVO(AgentToolBinding binding);

    List<AgentToolBindingVO> toVOList(List<AgentToolBinding> bindings);
}
