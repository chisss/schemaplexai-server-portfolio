package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.EvalTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

/**
 * 评估任务 Mapper
 */
@Mapper
public interface EvalTaskMapper extends BaseMapper<EvalTask> {

    /**
     * 原子递增已完成条目数（并行执行场景下避免整行 updateById 互相覆盖计数）
     */
    @Update("UPDATE sf_eval_task SET completed_items = COALESCE(completed_items, 0) + 1, "
            + "updated_at = CURRENT_TIMESTAMP WHERE id = #{taskId}")
    int incrementCompletedItems(String taskId);
}
