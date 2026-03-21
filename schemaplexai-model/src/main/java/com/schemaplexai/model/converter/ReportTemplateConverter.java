package com.schemaplexai.model.converter;

import com.schemaplexai.model.dto.monitor.ReportTemplateCreateRequest;
import com.schemaplexai.model.entity.ReportTemplate;
import com.schemaplexai.model.vo.monitor.ReportTemplateVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import java.util.List;

/**
 * 报表模板转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface ReportTemplateConverter {

    ReportTemplateVO toVO(ReportTemplate entity);
    List<ReportTemplateVO> toVOList(List<ReportTemplate> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    ReportTemplate fromCreateRequest(ReportTemplateCreateRequest request);
}
