package com.schemaplexai.model.converter;

import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.model.dto.workflow.WorkflowInstanceCreateRequest;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.vo.workflow.WorkflowInstanceVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 工作流实例转换器
 */
@Mapper(componentModel = "spring",
        unmappedSourcePolicy = ReportingPolicy.IGNORE,
        imports = {WorkflowInstanceStatusEnum.class})
public interface WorkflowInstanceConverter {

    @Mapping(target = "nodeExecutions", ignore = true)
    @Mapping(target = "triggerType", ignore = true)
    @Mapping(target = "templateName", ignore = true)
    @Mapping(target = "errorMessage", ignore = true)
    WorkflowInstanceVO toVO(WorkflowInstance instance);

    List<WorkflowInstanceVO> toVOList(List<WorkflowInstance> instances);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "status", expression = "java(WorkflowInstanceStatusEnum.PENDING.getCode())")
    @Mapping(target = "currentNodeId", ignore = true)
    @Mapping(target = "definition", ignore = true)
    @Mapping(target = "startedAt", ignore = true)
    @Mapping(target = "completedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "processInstanceId", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    WorkflowInstance fromCreateRequest(WorkflowInstanceCreateRequest request);
}
