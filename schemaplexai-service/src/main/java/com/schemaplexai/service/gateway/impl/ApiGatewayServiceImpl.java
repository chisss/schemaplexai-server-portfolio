package com.schemaplexai.service.gateway.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.ApiGatewayLogMapper;
import com.schemaplexai.dao.mapper.ApiGatewayMapper;
import com.schemaplexai.dao.mapper.ApiGatewayPolicyMapper;
import com.schemaplexai.model.dto.gateway.ApiGatewayCreateRequest;
import com.schemaplexai.model.dto.gateway.ApiGatewayLogQueryRequest;
import com.schemaplexai.model.dto.gateway.ApiGatewayPolicyRequest;
import com.schemaplexai.model.dto.gateway.ApiGatewayQueryRequest;
import com.schemaplexai.model.dto.gateway.ApiGatewayTestRequest;
import com.schemaplexai.model.dto.gateway.ApiGatewayUpdateRequest;
import com.schemaplexai.model.entity.ApiGateway;
import com.schemaplexai.model.entity.ApiGatewayLog;
import com.schemaplexai.model.entity.ApiGatewayPolicy;
import com.schemaplexai.model.vo.gateway.ApiGatewayLogVO;
import com.schemaplexai.model.vo.gateway.ApiGatewayPolicyVO;
import com.schemaplexai.model.vo.gateway.ApiGatewayTestResult;
import com.schemaplexai.model.vo.gateway.ApiGatewayVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.gateway.ApiGatewayExecutor;
import com.schemaplexai.service.gateway.ApiGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiGatewayServiceImpl implements ApiGatewayService {

    private final ApiGatewayMapper gatewayMapper;
    private final ApiGatewayPolicyMapper policyMapper;
    private final ApiGatewayLogMapper logMapper;
    private final ApiGatewayExecutor executor;
    private final EntityValidator entityValidator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiGatewayVO create(ApiGatewayCreateRequest request) {
        var gateway = new ApiGateway();
        BeanUtils.copyProperties(request, gateway);
        gateway.setStatus("active");
        gatewayMapper.insert(gateway);

        // 创建默认策略
        var policy = new ApiGatewayPolicy();
        policy.setGatewayId(gateway.getId());
        policy.setEnabled(true);
        policyMapper.insert(policy);

        log.info("创建API网关成功: id={}, name={}", gateway.getId(), gateway.getName());
        return toVO(gateway);
    }

    @Override
    public PageResult<ApiGatewayVO> page(ApiGatewayQueryRequest request) {
        var page = new Page<ApiGateway>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<ApiGateway>();

        var tenantId = SecurityUtil.getCurrentTenantId();
        if (tenantId != null) {
            wrapper.eq(ApiGateway::getTenantId, tenantId);
        }
        if (StringUtils.hasText(request.getDirection())) {
            wrapper.eq(ApiGateway::getDirection, request.getDirection());
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(ApiGateway::getStatus, request.getStatus());
        }
        if (StringUtils.hasText(request.getCategory())) {
            wrapper.eq(ApiGateway::getCategory, request.getCategory());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(w -> w.like(ApiGateway::getName, request.getKeyword())
                    .or().like(ApiGateway::getDescription, request.getKeyword())
                    .or().like(ApiGateway::getUrl, request.getKeyword()));
        }
        wrapper.orderByDesc(ApiGateway::getCreatedAt);

        var result = gatewayMapper.selectPage(page, wrapper);
        var voList = result.getRecords().stream().map(this::toVO).collect(Collectors.toList());
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public ApiGatewayVO getById(String id) {
        var gateway = entityValidator.requireExists(gatewayMapper, id, ResultCode.API_GATEWAY_NOT_FOUND);
        return toVO(gateway);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiGatewayVO update(String id, ApiGatewayUpdateRequest request) {
        var gateway = entityValidator.requireExists(gatewayMapper, id, ResultCode.API_GATEWAY_NOT_FOUND);

        if (StringUtils.hasText(request.getName())) gateway.setName(request.getName());
        if (request.getDescription() != null) gateway.setDescription(request.getDescription());
        if (StringUtils.hasText(request.getDirection())) gateway.setDirection(request.getDirection());
        if (StringUtils.hasText(request.getMethod())) gateway.setMethod(request.getMethod());
        if (StringUtils.hasText(request.getUrl())) gateway.setUrl(request.getUrl());
        if (request.getAuthType() != null) gateway.setAuthType(request.getAuthType());
        if (request.getAuthConfig() != null) gateway.setAuthConfig(request.getAuthConfig());
        if (request.getHeaders() != null) gateway.setHeaders(request.getHeaders());
        if (request.getQueryParams() != null) gateway.setQueryParams(request.getQueryParams());
        if (request.getRequestBodySchema() != null) gateway.setRequestBodySchema(request.getRequestBodySchema());
        if (request.getResponseBodySchema() != null) gateway.setResponseBodySchema(request.getResponseBodySchema());
        if (request.getContentType() != null) gateway.setContentType(request.getContentType());
        if (request.getTimeoutMs() != null) gateway.setTimeoutMs(request.getTimeoutMs());
        if (request.getRetryCount() != null) gateway.setRetryCount(request.getRetryCount());
        if (request.getCategory() != null) gateway.setCategory(request.getCategory());
        if (request.getTags() != null) gateway.setTags(request.getTags());
        if (StringUtils.hasText(request.getStatus())) gateway.setStatus(request.getStatus());

        gatewayMapper.updateById(gateway);
        log.info("更新API网关成功: id={}", id);
        return toVO(gateway);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        entityValidator.requireExists(gatewayMapper, id, ResultCode.API_GATEWAY_NOT_FOUND);
        gatewayMapper.deleteById(id);
        policyMapper.delete(new LambdaQueryWrapper<ApiGatewayPolicy>()
                .eq(ApiGatewayPolicy::getGatewayId, id));
        log.info("删除API网关成功: id={}", id);
    }

    @Override
    public ApiGatewayTestResult test(String id, ApiGatewayTestRequest request) {
        var gateway = entityValidator.requireExists(gatewayMapper, id, ResultCode.API_GATEWAY_NOT_FOUND);
        var result = executor.execute(gateway,
                request != null ? request.getHeaders() : null,
                request != null ? request.getQueryParams() : null,
                request != null ? request.getBody() : null);

        saveLog(gateway, result, "user", SecurityUtil.getCurrentUserId());
        return result;
    }

    @Override
    public ApiGatewayTestResult execute(String id, ApiGatewayTestRequest request,
                                         String callerType, String callerId) {
        var gateway = entityValidator.requireExists(gatewayMapper, id, ResultCode.API_GATEWAY_NOT_FOUND);
        var result = executor.execute(gateway,
                request != null ? request.getHeaders() : null,
                request != null ? request.getQueryParams() : null,
                request != null ? request.getBody() : null);

        saveLog(gateway, result, callerType, callerId);
        return result;
    }

    @Override
    public ApiGatewayPolicyVO getPolicy(String id) {
        entityValidator.requireExists(gatewayMapper, id, ResultCode.API_GATEWAY_NOT_FOUND);
        var policy = policyMapper.selectOne(new LambdaQueryWrapper<ApiGatewayPolicy>()
                .eq(ApiGatewayPolicy::getGatewayId, id));
        if (policy == null) {
            var vo = new ApiGatewayPolicyVO();
            vo.setGatewayId(id);
            return vo;
        }
        return toPolicyVO(policy);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiGatewayPolicyVO updatePolicy(String id, ApiGatewayPolicyRequest request) {
        entityValidator.requireExists(gatewayMapper, id, ResultCode.API_GATEWAY_NOT_FOUND);
        var policy = policyMapper.selectOne(new LambdaQueryWrapper<ApiGatewayPolicy>()
                .eq(ApiGatewayPolicy::getGatewayId, id));

        if (policy == null) {
            policy = new ApiGatewayPolicy();
            policy.setGatewayId(id);
            BeanUtils.copyProperties(request, policy, "id", "gatewayId");
            policyMapper.insert(policy);
        } else {
            if (request.getRateLimitPerMinute() != null) policy.setRateLimitPerMinute(request.getRateLimitPerMinute());
            if (request.getRateLimitPerHour() != null) policy.setRateLimitPerHour(request.getRateLimitPerHour());
            if (request.getRateLimitPerDay() != null) policy.setRateLimitPerDay(request.getRateLimitPerDay());
            if (request.getCostPerCall() != null) policy.setCostPerCall(request.getCostPerCall());
            if (request.getDailyCostLimit() != null) policy.setDailyCostLimit(request.getDailyCostLimit());
            if (request.getMonthlyCostLimit() != null) policy.setMonthlyCostLimit(request.getMonthlyCostLimit());
            if (request.getIpWhitelist() != null) policy.setIpWhitelist(request.getIpWhitelist());
            if (request.getDataMaskingRules() != null) policy.setDataMaskingRules(request.getDataMaskingRules());
            if (request.getAccessLevel() != null) policy.setAccessLevel(request.getAccessLevel());
            if (request.getRequireApproval() != null) policy.setRequireApproval(request.getRequireApproval());
            if (request.getComplianceTags() != null) policy.setComplianceTags(request.getComplianceTags());
            if (request.getEnabled() != null) policy.setEnabled(request.getEnabled());
            policyMapper.updateById(policy);
        }

        log.info("更新API网关策略成功: gatewayId={}", id);
        return toPolicyVO(policy);
    }

    @Override
    public PageResult<ApiGatewayLogVO> getLogs(String id, ApiGatewayLogQueryRequest request) {
        entityValidator.requireExists(gatewayMapper, id, ResultCode.API_GATEWAY_NOT_FOUND);
        var page = new Page<ApiGatewayLog>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<ApiGatewayLog>()
                .eq(ApiGatewayLog::getGatewayId, id);

        if (StringUtils.hasText(request.getCallerType())) {
            wrapper.eq(ApiGatewayLog::getCallerType, request.getCallerType());
        }
        if (request.getSuccess() != null) {
            wrapper.eq(ApiGatewayLog::getSuccess, request.getSuccess());
        }
        wrapper.orderByDesc(ApiGatewayLog::getCreatedAt);

        var result = logMapper.selectPage(page, wrapper);
        var voList = result.getRecords().stream().map(this::toLogVO).collect(Collectors.toList());
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public List<ApiGatewayVO> listAvailable() {
        var tenantId = SecurityUtil.getCurrentTenantId();
        var wrapper = new LambdaQueryWrapper<ApiGateway>()
                .eq(ApiGateway::getStatus, "active");
        if (tenantId != null) {
            wrapper.eq(ApiGateway::getTenantId, tenantId);
        }
        wrapper.orderByDesc(ApiGateway::getCreatedAt);
        return gatewayMapper.selectList(wrapper).stream()
                .map(this::toVO).collect(Collectors.toList());
    }

    private void saveLog(ApiGateway gateway, ApiGatewayTestResult result,
                         String callerType, String callerId) {
        var logEntity = new ApiGatewayLog();
        logEntity.setTenantId(gateway.getTenantId());
        logEntity.setGatewayId(gateway.getId());
        logEntity.setCallerType(callerType);
        logEntity.setCallerId(callerId);
        logEntity.setRequestUrl(gateway.getUrl());
        logEntity.setRequestMethod(gateway.getMethod());
        logEntity.setResponseStatus(result.getStatusCode());
        logEntity.setResponseBody(result.getResponseBody());
        logEntity.setDurationMs(result.getDurationMs());
        logEntity.setSuccess(result.getSuccess());
        logEntity.setErrorMessage(result.getErrorMessage());
        logEntity.setCreatedAt(LocalDateTime.now());
        logMapper.insert(logEntity);
    }

    private ApiGatewayVO toVO(ApiGateway entity) {
        var vo = new ApiGatewayVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private ApiGatewayPolicyVO toPolicyVO(ApiGatewayPolicy entity) {
        var vo = new ApiGatewayPolicyVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private ApiGatewayLogVO toLogVO(ApiGatewayLog entity) {
        var vo = new ApiGatewayLogVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
