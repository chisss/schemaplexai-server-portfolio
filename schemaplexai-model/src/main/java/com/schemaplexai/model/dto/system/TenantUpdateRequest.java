package com.schemaplexai.model.dto.system;

import lombok.Data;

/**
 * 更新租户请求DTO
 */
@Data
public class TenantUpdateRequest {

    /** 租户名称 */
    private String name;

    /** 描述 */
    private String description;

    /** Logo地址 */
    private String logoUrl;

    /** 状态: active/inactive */
    private String status;
}
