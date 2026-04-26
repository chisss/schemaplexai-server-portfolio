package com.schemaplexai.service.evaluation;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.evaluation.EvalTaskCreateRequest;
import com.schemaplexai.model.dto.evaluation.EvalTaskQueryRequest;
import com.schemaplexai.model.vo.evaluation.EvalTaskVO;

/**
 * 评估任务服务
 */
public interface EvalTaskService {

    EvalTaskVO create(EvalTaskCreateRequest request);

    PageResult<EvalTaskVO> page(EvalTaskQueryRequest request);

    EvalTaskVO getById(String id);

    EvalTaskVO run(String id);
}
