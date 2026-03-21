package com.schemaplexai.model.converter;

import com.schemaplexai.model.dto.workspace.WorkspaceCreateRequest;
import com.schemaplexai.model.entity.Workspace;
import com.schemaplexai.model.vo.workspace.WorkspaceVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 工作空间转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface WorkspaceConverter {

    @Mapping(target = "createdByName", ignore = true)
    WorkspaceVO toVO(Workspace entity);

    List<WorkspaceVO> toVOList(List<Workspace> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "workspaceStatus", ignore = true)
    @Mapping(target = "diskUsageMb", constant = "0L")
    @Mapping(target = "lastSyncAt", ignore = true)
    @Mapping(target = "errorMessage", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    Workspace fromCreateRequest(WorkspaceCreateRequest request);
}
