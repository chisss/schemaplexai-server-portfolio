package com.schemaplexai.model.dto.quality;

import lombok.Data;

/**
 * 意图缺陷查询请求
 */
@Data
public class IntentDefectQueryRequest {
    private String specId;
    /** 文档类型: requirements/design/tasks */
    private String docType;
    /** 缺陷类型: ambiguity/contradiction/omission/vagueness */
    private String defectType;
    /** 严重程度: critical/warning/info */
    private String severity;
    /** 状态: open/resolved/accepted */
    private String status;
    private String keyword;
    private Integer page = 1;
    private Integer size = 20;
}
