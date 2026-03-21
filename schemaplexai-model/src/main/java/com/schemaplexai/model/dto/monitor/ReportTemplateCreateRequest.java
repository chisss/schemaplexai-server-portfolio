package com.schemaplexai.model.dto.monitor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 报表模板创建请求
 */
@Data
public class ReportTemplateCreateRequest {
    @NotBlank(message = "名称不能为空")
    private String name;
    @NotBlank(message = "报表类型不能为空")
    private String reportType;
    @NotNull(message = "配置不能为空")
    private Map<String, Object> config;
    private String scheduleCron;
    private List<Map<String, Object>> notifyChannels;
}
