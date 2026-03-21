package com.schemaplexai.common.util;

/**
 * 安全上下文工具类 - 获取当前登录用户信息
 * 依赖Spring Security，在web模块中通过SecurityContext实现
 */
public final class SecurityUtil {

    private SecurityUtil() {}

    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_USERNAME = new ThreadLocal<>();

    public static void setCurrentUserId(String userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static String getCurrentUserId() {
        return CURRENT_USER_ID.get();
    }

    public static void setCurrentTenantId(String tenantId) {
        CURRENT_TENANT_ID.set(tenantId);
    }

    public static String getCurrentTenantId() {
        return CURRENT_TENANT_ID.get();
    }

    public static void setCurrentUsername(String username) {
        CURRENT_USERNAME.set(username);
    }

    public static String getCurrentUsername() {
        return CURRENT_USERNAME.get();
    }

    public static void clear() {
        CURRENT_USER_ID.remove();
        CURRENT_TENANT_ID.remove();
        CURRENT_USERNAME.remove();
    }
}
