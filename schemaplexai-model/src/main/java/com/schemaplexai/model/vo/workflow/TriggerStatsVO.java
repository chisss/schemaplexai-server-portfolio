package com.schemaplexai.model.vo.workflow;

import lombok.Builder;
import lombok.Data;

/**
 * 触发统计概览
 */
@Data
@Builder
public class TriggerStatsVO {

    private int manualCount;
    private int cronCount;
    private int cronEnabledCount;
    private int eventCount;
    private int eventEnabledCount;
}
