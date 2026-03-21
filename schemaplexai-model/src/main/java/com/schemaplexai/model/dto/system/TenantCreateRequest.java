package com.schemaplexai.model.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建租户请求DTO
 */
@Data
public class TenantCreateRequest {

    /** 租户名称 */
    @NotBlank(message = "租户名称不能为空")
    private String name;

    /** 租户编码 */
    @NotBlank(message = "租户编码不能为空")
    private String code;

    /** 描述 */
    private String description;

    /** Logo地址 */
    private String logoUrl;
}
