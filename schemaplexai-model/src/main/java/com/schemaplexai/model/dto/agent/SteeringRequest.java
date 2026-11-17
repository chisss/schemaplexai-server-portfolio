package com.schemaplexai.model.dto.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 执行中用户引导请求
 */
@Data
public class SteeringRequest {

    /** 引导内容（用户补充的最新信息，非任务替换） */
    @NotBlank(message = "引导内容不能为空")
    private String content;
}
