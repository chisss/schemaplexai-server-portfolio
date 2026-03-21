package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 审计日志表实体
 */
@Data
@TableName("sf_audit_log")
public class AuditLog implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户ID（可为NULL，系统级操作） */
    private String tenantId;

    /** 操作人ID */
    private String userId;

    /** 操作人用户名(冗余) */
    private String username;

    /** 操作动作 */
    private String action;

    /** 资源类型 */
    private String resource;

    /** 资源ID */
    private String resourceId;

    /** 操作详情 */
    private String detail;

    /** IP地址 */
    private String ip;

    /** User Agent */
    private String userAgent;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
