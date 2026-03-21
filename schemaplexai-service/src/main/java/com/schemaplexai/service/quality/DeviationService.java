package com.schemaplexai.service.quality;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.quality.DeviationDetectRequest;
import com.schemaplexai.model.dto.quality.DeviationQueryRequest;
import com.schemaplexai.model.dto.quality.DeviationStatusUpdateRequest;
import com.schemaplexai.model.vo.quality.DeviationStatisticsVO;
import com.schemaplexai.model.vo.quality.DeviationVO;
import com.schemaplexai.model.vo.quality.DetectTaskVO;

/**
 * 偏离检测服务
 */
public interface DeviationService {

    DetectTaskVO detect(DeviationDetectRequest request);

    PageResult<DeviationVO> page(DeviationQueryRequest request);

    DeviationVO getById(String id);

    void updateStatus(String id, DeviationStatusUpdateRequest request);

    DeviationStatisticsVO statistics(String specId, String startDate, String endDate);
}
