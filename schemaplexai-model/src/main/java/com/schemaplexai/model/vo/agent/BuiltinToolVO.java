package com.schemaplexai.model.vo.agent;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统内置工具 VO
 */
@Data
public class BuiltinToolVO {

    private String id;
    private String code;
    private String name;
    private String description;
    private String inputSchema;
    private String osSupport;
    private Boolean enabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
