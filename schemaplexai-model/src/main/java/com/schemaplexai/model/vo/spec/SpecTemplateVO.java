package com.schemaplexai.model.vo.spec;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Spec模板VO
 */
@Data
public class SpecTemplateVO {

    private String id;
    private String name;
    private String category;
    private String docType;
    private String content;
    private Boolean isBuiltin;
    private Integer usageCount;
    private String description;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
