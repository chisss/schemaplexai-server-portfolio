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

/**
 * 租户 Skill 安装记录实体
 */
@Data
@TableName("sf_skill_installation")
public class SkillInstallation implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField(fill = FieldFill.INSERT)
    private String tenantId;

    private String skillId;

    private String installedVersion;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private String installedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime installedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
