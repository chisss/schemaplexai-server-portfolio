package com.schemaplexai.model.converter;

import com.schemaplexai.common.enums.SkillStatusEnum;
import com.schemaplexai.model.dto.skill.SkillCreateRequest;
import com.schemaplexai.model.entity.Skill;
import com.schemaplexai.model.vo.skill.SkillVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 技能转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE,
        imports = {SkillStatusEnum.class})
public interface SkillConverter {

    /**
     * Skill -> SkillVO
     */
    @Mapping(target = "createdByName", ignore = true)
    @Mapping(target = "tenantId", source = "tenantId")
    SkillVO toVO(Skill entity);

    /**
     * Skill列表 -> SkillVO列表
     */
    List<SkillVO> toVOList(List<Skill> entities);

    /**
     * SkillCreateRequest -> Skill
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "status", expression = "java(SkillStatusEnum.ACTIVE.getCode())")
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Skill fromCreateRequest(SkillCreateRequest request);
}
