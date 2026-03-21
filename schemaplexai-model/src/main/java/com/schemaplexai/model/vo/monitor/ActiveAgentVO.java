package com.schemaplexai.model.vo.monitor;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 活跃Agent VO
 */
@Data
public class ActiveAgentVO {
    private String agentId;
    private String agentName;
    private String agentType;
    private String currentTaskId;
    private Integer progress;
    private LocalDateTime startTime;
}
