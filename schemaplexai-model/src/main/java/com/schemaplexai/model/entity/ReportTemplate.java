package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 报表模板表实体
 */
@Data
@TableName(value = "sf_report_template", autoResultMap = true)
public class ReportTemplate implements Serializable {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String tenantId;
    private String name;
    /** 报表类型: efficiency/quality/agent_performance/cost/project_progress/team_collaboration/custom */
    private String reportType;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> config;
    private String scheduleCron;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Object> notifyChannels;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
