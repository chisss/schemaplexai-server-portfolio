package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 用户记忆画像快照
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_user_memory_profile", autoResultMap = true)
public class UserMemoryProfile extends BaseEntity {

    /** 所属用户ID */
    private String userId;

    /** Agent ID，可为空 */
    private String agentId;

    /** 项目ID，可为空 */
    private String projectId;

    /** 画像作用域 */
    private String profileScope;

    /** Markdown 画像内容 */
    private String profileMarkdown;

    /** JSON 画像内容 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> profileJson;

    /** 版本号 */
    private Integer version;

    /** 来源记忆ID列表 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> sourceMemoryIds;

    /** 生成时间 */
    private LocalDateTime generatedAt;
}
