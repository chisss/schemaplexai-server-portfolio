package com.schemaplexai.model.vo.monitor;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 报表模板VO
 */
@Data
public class ReportTemplateVO {
    private String id;
    private String name;
    private String reportType;
    private Map<String, Object> config;
    private String scheduleCron;
    private List<Object> notifyChannels;
    private LocalDateTime createdAt;
}
