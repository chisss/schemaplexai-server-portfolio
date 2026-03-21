package com.schemaplexai.service.context.validator;

import com.schemaplexai.common.enums.ContextLevelEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.ContextEntityMapper;
import com.schemaplexai.model.entity.ContextEntity;
import com.schemaplexai.service.common.EntityValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 上下文业务校验器
 */
@Component
@RequiredArgsConstructor
public class ContextValidator {

    private final ContextEntityMapper contextEntityMapper;
    private final EntityValidator entityValidator;

    private static final Set<String> VALID_LEVELS = Arrays.stream(ContextLevelEnum.values())
            .map(ContextLevelEnum::getCode)
            .collect(Collectors.toSet());

    private static final Set<String> PROJECT_REQUIRED_LEVELS = Set.of(
            ContextLevelEnum.PROJECT.getCode(),
            ContextLevelEnum.TASK.getCode(),
            ContextLevelEnum.AGENT.getCode()
    );

    /**
     * 校验上下文名称唯一
     */
    public void validateNameUnique(String name) {
        entityValidator.checkUnique(contextEntityMapper, ContextEntity::getName, name,
                ResultCode.CONTEXT_NAME_DUPLICATE);
    }

    /**
     * 校验上下文层级合法性
     */
    public void validateContextLevel(String level) {
        if (!VALID_LEVELS.contains(level)) {
            throw new BusinessException(ResultCode.CONTEXT_LEVEL_INVALID);
        }
    }

    /**
     * 校验 project/task/agent 层级必须有 projectId
     */
    public void validateProjectRequired(String level, String projectId) {
        if (PROJECT_REQUIRED_LEVELS.contains(level) && !StringUtils.hasText(projectId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST);
        }
    }
}
