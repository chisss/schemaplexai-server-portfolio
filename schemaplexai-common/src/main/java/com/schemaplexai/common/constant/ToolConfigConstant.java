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

    // ==================== HTTP 工具执行相关 ====================

    /** HTTP 请求方法参数 key（Agent 调用时通过此 key 指定 HTTP 方法） */
    public static final String HTTP_METHOD_KEY = "_method";

    /** HTTP 响应体最大长度（防止 Agent 上下文爆炸） */
    public static final int HTTP_RESPONSE_MAX_LENGTH = 8192;

    /** HTTP 响应截断后缀 */
    public static final String HTTP_RESPONSE_TRUNCATED_SUFFIX = "...[truncated]";

    /** HTTP 请求中禁止注入的危险头名称（小写） */
    public static final java.util.Set<String> HTTP_FORBIDDEN_HEADERS = java.util.Set.of(
            "host", "transfer-encoding", "content-length", "connection"
    );

    // ==================== Skill 配置 key ====================

    /** Skill SCRIPT 类型 — 脚本内容 */
    public static final String SKILL_CONFIG_SCRIPT_CONTENT = "scriptContent";

    /** Skill SCRIPT 类型 — 脚本语言类型 */
    public static final String SKILL_CONFIG_SCRIPT_TYPE = "scriptType";

    /** Skill API 类型 — 请求 URL */
    public static final String SKILL_CONFIG_API_URL = "apiUrl";

    /** Skill API 类型 — 请求方法 */
    public static final String SKILL_CONFIG_API_METHOD = "method";

    /** Skill API 类型 — 请求头 */
    public static final String SKILL_CONFIG_API_HEADERS = "headers";

    /** Skill 配置 — 默认参数 */
    public static final String SKILL_CONFIG_DEFAULT_ARGS = "defaultArgs";

    /** Skill API 类型 — 认证 Token（从 AgentToolConfig 注入） */
    public static final String SKILL_CONFIG_AUTH_TOKEN = "authToken";
}
