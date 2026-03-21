package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统字典实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sf_dict")
public class SysDict extends BaseEntity {

    /** 字典名称 */
    private String dictName;

    /** 字典编码（全局唯一） */
    private String dictCode;

    /** 字典类型：biz=业务字典, i18n=国际化字典 */
    private String dictType;

    /** 备注 */
    private String remark;

    /** 状态：active/inactive */
    private String status;
}
