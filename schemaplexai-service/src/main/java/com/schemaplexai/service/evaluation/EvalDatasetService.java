package com.schemaplexai.service.evaluation;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.evaluation.EvalDatasetCreateRequest;
import com.schemaplexai.model.dto.evaluation.EvalDatasetItemSaveRequest;
import com.schemaplexai.model.dto.evaluation.EvalDatasetUpdateRequest;
import com.schemaplexai.model.vo.evaluation.EvalDatasetVO;

/**
 * 评估数据集服务
 */
public interface EvalDatasetService {

    EvalDatasetVO create(EvalDatasetCreateRequest request);

    PageResult<EvalDatasetVO> page(Integer page, Integer size, String keyword);

    EvalDatasetVO getById(String id);

    EvalDatasetVO update(String id, EvalDatasetUpdateRequest request);

    EvalDatasetVO saveItems(String id, EvalDatasetItemSaveRequest request);

    void delete(String id);
}
