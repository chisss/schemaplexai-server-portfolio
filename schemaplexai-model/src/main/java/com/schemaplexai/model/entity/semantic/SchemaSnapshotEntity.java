package com.schemaplexai.model.entity.semantic;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.schemaplexai.model.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/** Schema 快照持久化实体。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_schema_snapshot", autoResultMap = true)
public class SchemaSnapshotEntity extends BaseEntity {

    private String sourceId;
    private String fingerprint;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> schemaJson;

    private String status;
    private LocalDateTime scannedAt;
}
