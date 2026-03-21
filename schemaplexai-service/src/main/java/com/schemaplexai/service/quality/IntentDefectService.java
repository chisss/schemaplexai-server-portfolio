package com.schemaplexai.service.quality;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.quality.IntentDefectAnalyzeRequest;
import com.schemaplexai.model.dto.quality.IntentDefectQueryRequest;
import com.schemaplexai.model.vo.quality.AnalyzeTaskVO;
import com.schemaplexai.model.vo.quality.IntentDefectVO;

/**
 * 意图缺陷分析服务
 */
public interface IntentDefectService {

    AnalyzeTaskVO analyze(IntentDefectAnalyzeRequest request);

    PageResult<IntentDefectVO> page(IntentDefectQueryRequest request);

    IntentDefectVO getById(String id);

    void updateStatus(String id, String status);
}
