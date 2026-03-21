package com.schemaplexai.model.converter;

import com.schemaplexai.model.dto.workflow.WorkflowTemplateCreateRequest;
import com.schemaplexai.model.entity.WorkflowTemplate;
import com.schemaplexai.model.vo.workflow.WorkflowTemplateVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 工作流模板转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface WorkflowTemplateConverter {

    WorkflowTemplateVO toVO(WorkflowTemplate template);

    List<WorkflowTemplateVO> toVOList(List<WorkflowTemplate> templates);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "isBuiltin", constant = "false")
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    WorkflowTemplate fromCreateRequest(WorkflowTemplateCreateRequest request);
}
