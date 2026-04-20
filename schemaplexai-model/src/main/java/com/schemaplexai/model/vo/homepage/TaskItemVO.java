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
}
