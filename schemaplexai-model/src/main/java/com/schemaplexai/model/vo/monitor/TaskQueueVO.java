package com.schemaplexai.model.vo.monitor;

import lombok.Data;
import java.util.Map;

/**
 * 任务队列VO
 */
@Data
public class TaskQueueVO {
    private Integer totalPending;
    private Integer totalExecuting;
    private Map<String, Integer> queueByPriority;
    private Long oldestTaskWaitMs;
}
