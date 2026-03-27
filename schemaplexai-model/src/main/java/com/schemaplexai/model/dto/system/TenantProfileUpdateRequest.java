package com.schemaplexai.model.dto.system;

import lombok.Data;

import java.util.List;

/**
 * 更新租户行业配置请求DTO
 */
@Data
public class TenantProfileUpdateRequest {

    /** 行业: tech/finance/retail/healthcare/manufacturing */
    private String industry;

    /** 使用场景列表: dev_workflow/sales_workflow/marketing_workflow/ops_workflow */
    private List<String> scenarios;

    /** 开通能力配置 */
    private java.util.Map<String, Object> enabledCapabilities;
}
