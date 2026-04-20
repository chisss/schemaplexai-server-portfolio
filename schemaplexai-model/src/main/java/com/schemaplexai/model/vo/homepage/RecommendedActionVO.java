package com.schemaplexai.model.vo.homepage;

import lombok.Data;

/**
 * 推荐动作
 */
@Data
public class RecommendedActionVO {

    private String actionId;

    /** CONTINUE / UNBLOCK / APPROVE / INVESTIGATE */
    private String actionType;

    /** 动作句，如"处理 1 个高优先级安全事件" */
    private String title;

    private String targetUrl;
    private String targetId;
    private int priority;
}
