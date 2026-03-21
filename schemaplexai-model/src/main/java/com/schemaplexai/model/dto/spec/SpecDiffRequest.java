package com.schemaplexai.model.dto.spec;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 版本Diff对比请求
 */
@Data
public class SpecDiffRequest {

    /** 源版本ID */
    @NotBlank(message = "源版本ID不能为空")
    private String sourceVersionId;

    /** 目标版本ID */
    @NotBlank(message = "目标版本ID不能为空")
    private String targetVersionId;
}
