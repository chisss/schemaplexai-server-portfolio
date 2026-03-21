package com.schemaplexai.service.cicd.validator;

import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.CicdPipelineMapper;
import com.schemaplexai.model.entity.CicdPipeline;
import com.schemaplexai.service.common.EntityValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * CICD Pipeline业务校验器
 */
@Component
@RequiredArgsConstructor
public class CicdPipelineValidator {

    private final CicdPipelineMapper pipelineMapper;
    private final EntityValidator entityValidator;

    /**
     * 校验Pipeline名称唯一性
     */
    public void validateNameUnique(String name) {
        entityValidator.checkUnique(pipelineMapper, CicdPipeline::getName, name,
                ResultCode.CICD_PIPELINE_NAME_DUPLICATE);
    }
}
