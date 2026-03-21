package com.schemaplexai.model.vo.system;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户信息VO
 */
@Data
public class TenantVO {

    /** 租户ID */
    private String id;

    /** 租户名称 */
    private String name;

    /** 租户编码 */
    private String code;

    /** 描述 */
    private String description;

    /** Logo地址 */
    private String logoUrl;

    /** 状态: active/inactive */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
