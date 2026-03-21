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
 * 用户表实体（不继承BaseEntity，字段结构不完全一致）
 */
@Data
@TableName("sf_user")
public class User implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户ID */
    private String tenantId;

    /** 登录用户名 */
    private String username;

    /** 密码哈希(BCrypt) */
    private String passwordHash;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String phone;

    /** 真实姓名 */
    private String realName;

    /** 头像地址 */
    private String avatarUrl;

    /** 状态: active/inactive/locked */
    private String status;

    /** 登录类型: password/sso/oauth */
    private String loginType;

    /** 最近登录时间 */
    private LocalDateTime lastLoginAt;

    /** 最近登录IP */
    private String lastLoginIp;

    /** 创建人 */
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新人 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除: 0未删除, 1已删除 */
    @TableLogic
    private Integer deleted;
}
