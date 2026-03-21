package com.schemaplexai.service.skill.validator;

import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.SkillMapper;
import com.schemaplexai.model.entity.Skill;
import com.schemaplexai.service.common.EntityValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 技能业务校验器
 */
@Component
@RequiredArgsConstructor
public class SkillValidator {

    private final SkillMapper skillMapper;
    private final EntityValidator entityValidator;

    /**
     * 校验技能名称唯一性
     */
    public void validateNameUnique(String name) {
        entityValidator.checkUnique(skillMapper, Skill::getName, name, ResultCode.SKILL_NAME_DUPLICATE);
    }

    /**
     * 校验技能名称唯一性（排除自身）
     */
    public void validateNameUniqueExcludeSelf(String name, String excludeId) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Skill>();
        wrapper.eq(Skill::getName, name).ne(Skill::getId, excludeId);
        if (skillMapper.selectCount(wrapper) > 0) {
            throw new com.schemaplexai.common.exception.BusinessException(ResultCode.SKILL_NAME_DUPLICATE);
        }
    }
}
