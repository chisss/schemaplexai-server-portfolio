package com.schemaplexai.common.constant;

/**
 * 系统租户与租户边界常量
 */
public final class SystemTenantConstant {

    private SystemTenantConstant() {}

    /** 前端或网关显式传入的租户头 */
    public static final String TENANT_HEADER = "X-Tenant-Id";

    /** 初始化数据使用的系统租户 UUID */
    public static final String SYSTEM_TENANT_ID = "00000000-0000-0000-0000-000000000000";

    /** fail-closed 统一错误文案 */
    public static final String TENANT_CONTEXT_MISSING_MESSAGE = "租户上下文缺失";
}
