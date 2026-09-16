package com.schemaplexai.model.entity.semantic;

import com.baomidou.mybatisplus.annotation.TableName;
import com.schemaplexai.model.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 语义模型控制面实体。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sf_semantic_model")
public class SemanticModelEntity extends BaseEntity {

    private String name;
    private String domain;
    private String description;
    private String activeVersionId;
    private String status;
    private Long revision;
}
