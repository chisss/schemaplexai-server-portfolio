package com.schemaplexai.model.converter;

import com.schemaplexai.model.dto.spec.SpecTemplateCreateRequest;
import com.schemaplexai.model.entity.SpecTemplate;
import com.schemaplexai.model.vo.spec.SpecTemplateVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * Spec模板转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface SpecTemplateConverter {

    SpecTemplateVO toVO(SpecTemplate entity);

    List<SpecTemplateVO> toVOList(List<SpecTemplate> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "isBuiltin", constant = "false")
    @Mapping(target = "usageCount", constant = "0")
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    SpecTemplate fromCreateRequest(SpecTemplateCreateRequest request);
}
