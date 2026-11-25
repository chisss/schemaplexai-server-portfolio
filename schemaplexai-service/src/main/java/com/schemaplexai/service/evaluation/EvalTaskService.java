package com.schemaplexai.service.evaluation;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.evaluation.EvalTaskCreateRequest;
import com.schemaplexai.model.dto.evaluation.EvalTaskQueryRequest;
import com.schemaplexai.model.vo.evaluation.EvalTaskSummaryVO;
import com.schemaplexai.model.vo.evaluation.EvalTaskVerdictVO;
import com.schemaplexai.model.vo.evaluation.EvalTaskVO;

/**
 * 评估任务服务
 */
public interface EvalTaskService {

    EvalTaskVO create(EvalTaskCreateRequest request);

    /**
     * 分页查询（返回概要，不含结果明细；详情走 getById）
     */
    PageResult<EvalTaskSummaryVO> page(EvalTaskQueryRequest request);

    EvalTaskVO getById(String id);

    EvalTaskVO run(String id);

    /**
     * 门禁判定：按任务快照阈值输出 PASS/FAIL 结论（CI 回归门禁端点）
     *
     * @param id 任务 ID
     * @param passRateThreshold 达标率门槛（1-100，默认 100=严格全过门禁；
     *                          <100 时通过率达标即 PASS）
     */
    EvalTaskVerdictVO verdict(String id, Integer passRateThreshold);
}
