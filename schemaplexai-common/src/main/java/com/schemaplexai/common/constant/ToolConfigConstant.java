package com.schemaplexai.common.constant;

/**
 * 工具配置相关常量
 */
public final class ToolConfigConstant {

    private ToolConfigConstant() {}

    /** 工具加密密钥环境变量名 */
    public static final String ENV_TOOL_CONFIG_AES_KEY = "TOOL_CONFIG_AES_KEY";

    /** 工具工作空间基础路径环境变量名 */
    public static final String ENV_TOOL_WORKSPACE_BASE_PATH = "TOOL_WORKSPACE_BASE_PATH";

    /** 敏感字段默认掩码值 */
    public static final String DEFAULT_MASK_VALUE = "********";

    /** AES-GCM IV 长度（字节） */
    public static final int AES_GCM_IV_LENGTH = 12;

    /** AES-GCM Tag 长度（字节） */
    public static final int AES_GCM_TAG_LENGTH = 128;

    /** 默认工具工作空间 */
    public static final String DEFAULT_WORKSPACE_PATH = "/tmp/schemaplexai/tools";

    /** 路径敏感键名 */
    public static final String[] PATH_KEY_NAMES = {
            "path", "file", "dir", "directory", "workspace", "filepath", "filename"
    };

    /** 敏感键名关键词 */
    public static final String[] SENSITIVE_KEY_WORDS = {
            "password", "secret", "token", "api_key", "apikey", "private_key",
            "access_key", "credential", "auth", "cert", "key"
    };
}
