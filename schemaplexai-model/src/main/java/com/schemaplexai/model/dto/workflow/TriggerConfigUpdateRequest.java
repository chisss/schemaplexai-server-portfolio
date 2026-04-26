package com.schemaplexai.model.dto.workflow;

import lombok.Data;

import java.util.Map;

/**
 * 更新触发节点配置请求（定时/事件），支持切换触发类型
 */
@Data
public class TriggerConfigUpdateRequest {

    /** 触发类型: trigger_manual / trigger_cron / trigger_event，传入时切换触发类型 */
    private String triggerType;

    // ── cron 专属 ──
    private String cronExpression;
    private String timezone;

    // ── event 专属 ──
    private String eventType;
    /** 事件触发钩子/触发点，如 webhook URL、MQ routingKey、业务事件编码 */
    private String eventHook;
    private String eventFilter;

    // ── 通用 ──
    private Map<String, Object> triggerInputs;

    /** 是否启用 */
    private Boolean enabled;
}
