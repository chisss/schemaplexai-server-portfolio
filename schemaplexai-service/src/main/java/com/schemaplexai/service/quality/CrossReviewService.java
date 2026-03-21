package com.schemaplexai.service.quality;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.quality.CrossReviewCreateRequest;
import com.schemaplexai.model.vo.quality.CrossReviewVO;

/**
 * 多模型交叉审查服务
 */
public interface CrossReviewService {

    CrossReviewVO create(CrossReviewCreateRequest request);

    PageResult<CrossReviewVO> page(String specId, String status, Integer page, Integer size);

    CrossReviewVO getById(String id);
}
