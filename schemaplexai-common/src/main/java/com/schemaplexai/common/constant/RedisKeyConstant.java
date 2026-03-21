package com.schemaplexai.common.constant;

/**
 * Redis Key 常量
 */
public final class RedisKeyConstant {

    private RedisKeyConstant() {}

    /** Key前缀 */
    private static final String PREFIX = "sf:";

    // ========== 认证 ==========
    /** RefreshToken: sf:auth:token:refresh:{userId} */
    public static final String AUTH_REFRESH_TOKEN = PREFIX + "auth:token:refresh:";

    /** 用户信息缓存: sf:auth:user:{userId} */
    public static final String AUTH_USER = PREFIX + "auth:user:";

    // ========== Agent ==========
    /** Agent配置缓存: sf:agent:config:{agentId} */
    public static final String AGENT_CONFIG = PREFIX + "agent:config:";

    // ========== 上下文 ==========
    /** 共享上下文: sf:context:shared:{contextId} */
    public static final String CONTEXT_SHARED = PREFIX + "context:shared:";

    // ========== 成本 ==========
    /** 项目预算缓存: sf:cost:budget:{projectId} */
    public static final String COST_BUDGET = PREFIX + "cost:budget:";

    // ========== 分布式锁 ==========
    /** Spec提交锁: sf:lock:spec:submit:{specId} */
    public static final String LOCK_SPEC_SUBMIT = PREFIX + "lock:spec:submit:";

    // ========== 国际化 ==========
    /** 翻译文案缓存: sf:i18n:messages:{locale} */
    public static final String I18N_MESSAGES = PREFIX + "i18n:messages:";
}
