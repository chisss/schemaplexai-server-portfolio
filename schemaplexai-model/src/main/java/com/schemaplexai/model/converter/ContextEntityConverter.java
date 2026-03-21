package com.schemaplexai.model.converter;

import com.schemaplexai.common.constants.CommonConstants;
import com.schemaplexai.model.dto.context.ContextCreateRequest;
import com.schemaplexai.model.entity.ContextEntity;
import com.schemaplexai.model.entity.ContextSnapshot;
import com.schemaplexai.model.vo.context.ContextSnapshotVO;
import com.schemaplexai.model.vo.context.ContextVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 上下文实体转换器
 */
@Mapper(componentModel = "spring",
        unmappedSourcePolicy = ReportingPolicy.IGNORE,
        imports = {CommonConstants.class})
public interface ContextEntityConverter {

    ContextVO toVO(ContextEntity entity);

    List<ContextVO> toVOList(List<ContextEntity> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "status", expression = "java(CommonConstants.STATUS_ACTIVE)")
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    ContextEntity fromCreateRequest(ContextCreateRequest request);

    ContextSnapshotVO toSnapshotVO(ContextSnapshot snapshot);

    List<ContextSnapshotVO> toSnapshotVOList(List<ContextSnapshot> snapshots);
}
