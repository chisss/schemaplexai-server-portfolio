package com.schemaplexai.model.converter;

import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.enums.SpecLifecycleModeEnum;
import com.schemaplexai.common.enums.SpecStatusEnum;
import com.schemaplexai.model.dto.spec.SpecCreateRequest;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.vo.spec.SpecVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * Spec实体转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE,
        imports = {SpecStatusEnum.class, SpecLifecycleModeEnum.class, CommonConstant.class})
public interface SpecConverter {

    /**
     * Spec -> SpecVO（documents 需要额外加载）
     */
    @Mapping(target = "documents", ignore = true)
    SpecVO toVO(Spec spec);

    List<SpecVO> toVOList(List<Spec> specs);

    /**
     * SpecCreateRequest -> Spec
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "version", expression = "java(CommonConstant.SPEC_INITIAL_VERSION)")
    @Mapping(target = "status", expression = "java(SpecStatusEnum.DRAFT.getCode())")
    @Mapping(target = "owner", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    @Mapping(target = "lifecycleMode", expression = "java(SpecLifecycleModeEnum.WORKFLOW.getCode())")
    @Mapping(target = "currentNodeId", ignore = true)
    @Mapping(target = "currentNodeType", ignore = true)
    @Mapping(target = "currentNodeLabel", ignore = true)
    @Mapping(target = "workflowStatusSnapshot", ignore = true)
    @Mapping(target = "workflowInstanceId", ignore = true)
    @Mapping(target = "artifactDocPath", ignore = true)
    @Mapping(target = "primaryArtifactId", ignore = true)
    Spec fromCreateRequest(SpecCreateRequest request);
}
