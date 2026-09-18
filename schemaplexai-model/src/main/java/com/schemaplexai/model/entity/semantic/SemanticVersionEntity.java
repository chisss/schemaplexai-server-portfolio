package com.schemaplexai.model.entity.semantic;

import com.baomidou.mybatisplus.annotation.TableName;
import com.schemaplexai.model.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 语义模型版本实体。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sf_semantic_version")
public class SemanticVersionEntity extends BaseEntity {

    private String modelId;
    private Integer versionNo;
    private String graphIri;
    private String sourceSnapshotId;
    private String status;
    private String checksum;
    private Long tripleCount;
    private String validationReport;
    private LocalDateTime publishedAt;
    private Long revision;
}
