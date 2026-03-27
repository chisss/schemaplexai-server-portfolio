package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工作流节点类型枚举
 * 对应前端 NodeType 类型定义
 */
@Getter
@AllArgsConstructor
public enum NodeTypeEnum {

    // 触发节点
    TRIGGER_MANUAL("trigger_manual", "手动触发", "trigger"),
    TRIGGER_CRON("trigger_cron", "定时触发", "trigger"),
    TRIGGER_EVENT("trigger_event", "事件触发", "trigger"),

    // 执行节点
    AGENT("agent", "Agent 节点", "execution"),
    SCRIPT("script", "脚本节点", "execution"),
    API_CALL("api_call", "API 调用", "execution"),
    NOTIFICATION("notification", "通信渠道", "execution"),
    DATABASE("database", "数据库操作", "execution"),

    // 控制节点
    HUMAN_REVIEW("human_review", "人工审核", "control"),
    CONDITION("condition", "条件分支", "control"),
    PARALLEL("parallel", "并行网关", "control"),
    LOOP("loop", "循环", "control"),

    // 质量节点
    DEVIATION_ANALYSIS("deviation_analysis", "偏离分析", "quality"),
    QUALITY_REPORT("quality_report", "质量报告", "quality"),

    // 终止节点
    END("end", "结束节点", "end");

    /** 节点类型标识（与前端 NodeType 一致） */
    private final String code;

    /** 中文描述 */
    private final String description;

    /** 节点分类 */
    private final String category;

    /**
     * 根据 code 获取枚举
     */
    public static NodeTypeEnum fromCode(String code) {
        for (NodeTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的节点类型: " + code);
    }

    /**
     * 判断是否为触发节点
     */
    public boolean isTrigger() {
        return "trigger".equals(this.category);
    }

    /**
     * 判断是否为结束节点
     */
    public boolean isEnd() {
        return this == END;
    }

    /**
     * 校验节点类型代码是否合法
     */
    public static boolean isValid(String code) {
        for (NodeTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return true;
            }
        }
        return false;
    }
}
