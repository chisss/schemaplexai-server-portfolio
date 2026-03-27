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

    /** 通用状态：启用 */
    public static final String STATUS_ACTIVE = "active";

    /** 通用状态：停用 */
    public static final String STATUS_INACTIVE = "inactive";

    /** 登录类型：密码登录 */
    public static final String LOGIN_TYPE_PASSWORD = "password";

    /** Spec初始版本号 */
    public static final String SPEC_INITIAL_VERSION = "1.0.0";

    /** 全部权限通配符 */
    public static final String PERMISSION_ALL = "*";

    /** 审批结果：通过 */
    public static final String APPROVAL_RESULT_APPROVED = "approved";

    /** 审批结果：拒绝 */
    public static final String APPROVAL_RESULT_REJECTED = "rejected";
}
