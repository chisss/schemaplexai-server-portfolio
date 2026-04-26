package com.schemaplexai.model.vo.workflow;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 触发配置视图
 */
@Data
@Builder
public class TriggerConfigVO {

    private String templateId;
    private String templateName;
    private String category;
    /** trigger_manual / trigger_cron / trigger_event */
    private String triggerType;
    /** published / draft / archived */
    private String templateStatus;
    /** 定时/事件触发是否启用 */
    private boolean enabled;

    // ── cron 专属 ──
    private String cronExpression;
    private String timezone;
    /** 人类可读描述，如 "每天 09:00 执行" */
    private String cronHumanReadable;

    // ── event 专属 ──
    private String eventType;
    /** 事件触发钩子/触发点，如 webhook URL、MQ routingKey、业务事件编码 */
    private String eventHook;
    private String eventFilter;

    // ── 通用 ──
    private Map<String, Object> triggerInputs;
    private LocalDateTime lastTriggeredAt;
    private String lastTriggerStatus;
}
