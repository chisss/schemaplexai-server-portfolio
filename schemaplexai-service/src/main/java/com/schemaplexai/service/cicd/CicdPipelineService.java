package com.schemaplexai.service.cicd;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.cicd.CicdPipelineCreateRequest;
import com.schemaplexai.model.dto.cicd.CicdPipelineQueryRequest;
import com.schemaplexai.model.dto.cicd.CicdPipelineUpdateRequest;
import com.schemaplexai.model.vo.cicd.CicdPipelineVO;

import java.util.Map;

/**
 * CICD Pipeline服务接口
 */
public interface CicdPipelineService {

    CicdPipelineVO create(CicdPipelineCreateRequest request);

    PageResult<CicdPipelineVO> page(CicdPipelineQueryRequest request);

    CicdPipelineVO getById(String id);

    CicdPipelineVO update(String id, CicdPipelineUpdateRequest request);

    void delete(String id);

    Map<String, Object> trigger(String id);
}
