package com.schemaplexai.model.converter;

import com.schemaplexai.model.dto.context.ContextItemCreateRequest;
import com.schemaplexai.model.entity.ContextItem;
import com.schemaplexai.model.vo.context.ContextItemVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 上下文条目转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface ContextItemConverter {

    @Mapping(target = "isAgentInstructions", ignore = true)
    @Mapping(target = "vectorStatus", ignore = true)
    @Mapping(target = "vectorUpdatedAt", ignore = true)
    @Mapping(target = "chunkCount", ignore = true)
    ContextItemVO toVO(ContextItem item);

    List<ContextItemVO> toVOList(List<ContextItem> items);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "contextId", ignore = true)
    @Mapping(target = "tokenCount", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    ContextItem fromCreateRequest(ContextItemCreateRequest request);
}
