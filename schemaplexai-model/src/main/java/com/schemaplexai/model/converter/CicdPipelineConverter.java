package com.schemaplexai.model.converter;

import com.schemaplexai.common.enums.CicdPipelineStatusEnum;
import com.schemaplexai.model.dto.cicd.CicdPipelineCreateRequest;
import com.schemaplexai.model.entity.CicdPipeline;
import com.schemaplexai.model.vo.cicd.CicdPipelineVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * CICD Pipeline转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE,
        imports = {CicdPipelineStatusEnum.class})
public interface CicdPipelineConverter {

    @Mapping(target = "createdByName", ignore = true)
    CicdPipelineVO toVO(CicdPipeline entity);

    List<CicdPipelineVO> toVOList(List<CicdPipeline> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "status", expression = "java(CicdPipelineStatusEnum.INACTIVE.getCode())")
    @Mapping(target = "lastRunAt", ignore = true)
    @Mapping(target = "lastRunStatus", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    CicdPipeline fromCreateRequest(CicdPipelineCreateRequest request);
}
