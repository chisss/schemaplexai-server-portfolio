package com.schemaplexai.common.constant;

/**
 * 通用常量
 */
public final class CommonConstant {

    private CommonConstant() {}

    /** 默认页码 */
    public static final int DEFAULT_PAGE = 1;

    /** 默认每页大小 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 最大每页大小 */
    public static final int MAX_PAGE_SIZE = 100;

    /** 逻辑删除-未删除 */
    public static final int NOT_DELETED = 0;

    /** 逻辑删除-已删除 */
    public static final int DELETED = 1;

    /** 默认语言 */
    public static final String DEFAULT_LOCALE = "zh-CN";

    /** 超级管理员角色 */
    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    /** 系统租户标识 */
    public static final String SYSTEM_TENANT = "system";
}
