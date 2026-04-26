package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型评估任务
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_eval_task", autoResultMap = true)
public class EvalTask extends BaseEntity {

    /**
     * 任务名称
     */
    private String name;

    /**
     * 数据集 ID
     */
    private String datasetId;

    /**
     * 参与评估的模型列表
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> modelIds;

    /**
     * 任务状态：PENDING/RUNNING/DONE/FAILED
     */
    private String status;

    /**
     * 总处理条目数（数据集条目数 * 模型数）
     */
    private Integer totalItems;

    /**
     * 已完成条目数
     */
    private Integer completedItems;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 开始时间
     */
    private LocalDateTime startedAt;

    /**
     * 完成时间
     */
    private LocalDateTime finishedAt;
}
