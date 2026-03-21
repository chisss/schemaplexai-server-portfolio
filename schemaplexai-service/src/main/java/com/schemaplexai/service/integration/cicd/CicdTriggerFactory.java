package com.schemaplexai.service.integration.cicd;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CICD 触发器工厂
 * 通过 Spring IoC 自动收集所有 CicdTrigger 实现
 */
@Slf4j
@Component
public class CicdTriggerFactory {

    private final Map<String, CicdTrigger> triggerMap = new HashMap<>();

    public CicdTriggerFactory(List<CicdTrigger> triggers) {
        for (CicdTrigger trigger : triggers) {
            triggerMap.put(trigger.getPipelineType(), trigger);
            log.info("注册 CICD 触发器: type={}", trigger.getPipelineType());
        }
    }

    /**
     * 根据 Pipeline 类型获取对应的触发器
     */
    public CicdTrigger getTrigger(String pipelineType) {
        CicdTrigger trigger = triggerMap.get(pipelineType);
        if (trigger == null) {
            throw new IllegalArgumentException("不支持的 Pipeline 类型: " + pipelineType);
        }
        return trigger;
    }
}
