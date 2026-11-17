package com.schemaplexai.model.vo.homepage;

import lombok.Data;

/**
 * 任务项
 */
@Data
public class TaskItemVO {

    private String id;

    /** workflow / approval / quality / security / agent_execution */
    private String itemType;

    private String title;
    private String status;

    /** CRITICAL / HIGH / MEDIUM / LOW */
    private String severity;

    private String targetUrl;
    private String updatedAt;

    /** 当前任务负责人用户 ID */
    private String ownerUserId;

    /** 当前任务负责人角色 */
    private String ownerRole;

    /** SLA 截止时间 */
    private String slaDueAt;

    /** 影响范围 */
    private String impactScope;

    /** 证据数量 */
    private Integer evidenceCount;

    /** 是否需要当前用户处理 */
    private Boolean requiresAction;
}
