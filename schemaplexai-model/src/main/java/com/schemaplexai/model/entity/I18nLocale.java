package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 国际化语言配置表实体（不继承BaseEntity，使用Long id）
 */
@Data
@TableName("sf_i18n_locale")
public class I18nLocale implements Serializable {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** BCP 47 语言代码，如 zh-CN */
    private String code;

    /** 显示名称，如 简体中文 */
    private String name;

    /** emoji旗帜 */
    private String flag;

    /** 是否启用 */
    private Boolean enabled;

    /** 排序 */
    private Integer sortOrder;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
