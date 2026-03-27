package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "sf_plugin_catalog", autoResultMap = true)
public class PluginCatalog implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    private String pluginUid;

    private String name;

    private String displayName;

    private String description;

    private String vendor;

    private String pluginType;

    private String version;

    private String sourceUrl;

    private String iconUrl;

    private String signature;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> manifest;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
