package com.schemaplexai.model.vo.homepage;

import lombok.Data;

/**
 * 风险告警
 */
@Data
public class RiskAlertVO {

    private String id;

    /** security / quality / workflow / system */
    private String alertType;

    /** CRITICAL / HIGH / MEDIUM / LOW */
    private String severity;

    private String title;
    private String description;
    private String targetUrl;
    private String createdAt;
}
