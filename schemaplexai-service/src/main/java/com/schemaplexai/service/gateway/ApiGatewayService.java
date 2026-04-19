package com.schemaplexai.service.gateway;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.gateway.ApiGatewayCreateRequest;
import com.schemaplexai.model.dto.gateway.ApiGatewayLogQueryRequest;
import com.schemaplexai.model.dto.gateway.ApiGatewayPolicyRequest;
import com.schemaplexai.model.dto.gateway.ApiGatewayQueryRequest;
import com.schemaplexai.model.dto.gateway.ApiGatewayTestRequest;
import com.schemaplexai.model.dto.gateway.ApiGatewayUpdateRequest;
import com.schemaplexai.model.vo.gateway.ApiGatewayLogVO;
import com.schemaplexai.model.vo.gateway.ApiGatewayPolicyVO;
import com.schemaplexai.model.vo.gateway.ApiGatewayTestResult;
import com.schemaplexai.model.vo.gateway.ApiGatewayVO;

import java.util.List;

public interface ApiGatewayService {

    ApiGatewayVO create(ApiGatewayCreateRequest request);

    PageResult<ApiGatewayVO> page(ApiGatewayQueryRequest request);

    ApiGatewayVO getById(String id);

    ApiGatewayVO update(String id, ApiGatewayUpdateRequest request);

    void delete(String id);

    ApiGatewayTestResult test(String id, ApiGatewayTestRequest request);

    ApiGatewayTestResult execute(String id, ApiGatewayTestRequest request, String callerType, String callerId);

    ApiGatewayPolicyVO getPolicy(String id);

    ApiGatewayPolicyVO updatePolicy(String id, ApiGatewayPolicyRequest request);

    PageResult<ApiGatewayLogVO> getLogs(String id, ApiGatewayLogQueryRequest request);

    List<ApiGatewayVO> listAvailable();
}
