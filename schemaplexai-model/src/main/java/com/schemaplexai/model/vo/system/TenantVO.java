package com.schemaplexai.model.vo.system;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

    /** 行业类型 */
    private String industry;

    /** 使用场景列表 */
    private List<String> scenarios;

    /** 开通能力配置 */
    private Map<String, Object> enabledCapabilities;

    /** 模板初始化状态: pending/running/done/failed */
    private String initStatus;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
