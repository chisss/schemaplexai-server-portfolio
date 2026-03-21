package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 系统字典项实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_dict_item", autoResultMap = true)
public class SysDictItem extends BaseEntity {

    /** 字典ID */
    private String dictId;

    /** 字典编码（冗余，便于查询） */
    private String dictCode;

    /** 选项标签（前端显示文本） */
    private String itemLabel;

    /** 选项值 */
    private String itemValue;

    /** 描述/提示文本 */
    private String description;

    /** 排序序号 */
    private Integer sortOrder;

    /** 扩展信息（JSONB：如 apiUrl、region 等） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> extra;

    /** 状态：active/inactive */
    private String status;
}
