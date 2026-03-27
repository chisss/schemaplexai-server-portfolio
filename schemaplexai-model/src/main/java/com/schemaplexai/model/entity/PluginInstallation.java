package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("sf_plugin_installation")
public class PluginInstallation implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField(fill = FieldFill.INSERT)
    private String tenantId;

    private String pluginUid;

    private String pluginCatalogId;

    private String installedVersion;

    private String status;

    private Boolean signatureVerified;

    private String compatibilityStatus;

    @TableField(fill = FieldFill.INSERT)
    private String installedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime installedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
