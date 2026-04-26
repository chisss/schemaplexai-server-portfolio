package com.schemaplexai.model.vo.workflow;

import lombok.Builder;
import lombok.Data;

/**
 * 手动触发结果
 */
@Data
@Builder
public class ManualTriggerResultVO {

    private String instanceId;
    private String instanceName;
    private String status;
}
