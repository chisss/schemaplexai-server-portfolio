package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 国际化翻译文案表实体（不继承BaseEntity，使用Long id）
 */
@Data
@TableName("sf_i18n_message")
public class I18nMessage implements Serializable {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 语言代码 */
    private String locale;

    /** 点分隔key，如 common.confirm */
    private String msgKey;

    /** 翻译文案 */
    private String msgValue;

    /** 含义说明，便于翻译人员理解 */
    private String description;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
