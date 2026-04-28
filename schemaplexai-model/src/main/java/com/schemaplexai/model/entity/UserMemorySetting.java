package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户记忆设置
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_user_memory_setting", autoResultMap = true)
public class UserMemorySetting extends BaseEntity {

    /** 所属用户ID */
    private String userId;

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
