package com.schemaplexai.model.dto.user;

import lombok.Data;

/**
 * 更新用户记忆设置请求
 */
@Data
public class UserMemorySettingUpdateRequest {

    /** 记忆总开关 */
    private Boolean memoryEnabled;

    /** 是否引用已保存记忆 */
    private Boolean referenceSavedMemory;

    /** 是否引用历史聊天 */
    private Boolean referenceChatHistory;

    /** 是否自动提取 */
    private Boolean autoExtractEnabled;

    /** 敏感信息策略 */
    private String sensitiveMemoryPolicy;

    /** 保留天数 */
    private Integer retentionDays;
}
