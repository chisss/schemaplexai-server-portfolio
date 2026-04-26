package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 模型评估数据集条目
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_eval_dataset_item", autoResultMap = true)
public class EvalDatasetItem extends BaseEntity {

    /**
     * 所属数据集 ID
     */
    private String datasetId;

    /**
     * 评估输入
     */
    private String inputText;

    /**
     * 期望输出
     */
    private String expectedOutput;

    /**
     * 额外元数据
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    /**
     * 排序号
     */
    private Integer sortOrder;
}
