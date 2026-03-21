package com.schemaplexai.service.monitor.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AuditLogMapper;
import com.schemaplexai.model.converter.AuditLogConverter;
import com.schemaplexai.model.dto.monitor.AuditLogQueryRequest;
import com.schemaplexai.model.entity.AuditLog;
import com.schemaplexai.model.vo.monitor.AuditLogVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.monitor.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 审计日志服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogMapper auditLogMapper;
    private final AuditLogConverter auditLogConverter;
    private final EntityValidator entityValidator;

    @Override
    public PageResult<AuditLogVO> page(AuditLogQueryRequest request) {
        log.info("分页查询审计日志, userId={}, action={}, resource={}",
                request.getUserId(), request.getAction(), request.getResource());

        var pageParam = new Page<AuditLog>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<AuditLog>();

        // 可选条件：操作人ID
        if (StringUtils.hasText(request.getUserId())) {
            wrapper.eq(AuditLog::getUserId, request.getUserId());
        }

        // 可选条件：操作动作
        if (StringUtils.hasText(request.getAction())) {
            wrapper.eq(AuditLog::getAction, request.getAction());
        }

        // 可选条件：资源类型
        if (StringUtils.hasText(request.getResource())) {
            wrapper.eq(AuditLog::getResource, request.getResource());
        }

        // 可选条件：时间范围
        if (request.getStartTime() != null) {
            wrapper.ge(AuditLog::getCreatedAt, request.getStartTime());
        }
        if (request.getEndTime() != null) {
            wrapper.le(AuditLog::getCreatedAt, request.getEndTime());
        }

        // 按创建时间倒序排列
        wrapper.orderByDesc(AuditLog::getCreatedAt);

        var result = auditLogMapper.selectPage(pageParam, wrapper);
        var voList = auditLogConverter.toVOList(result.getRecords());
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public AuditLogVO getById(String id) {
        log.info("查询审计日志详情, id={}", id);

        var auditLog = entityValidator.requireExists(auditLogMapper, id, ResultCode.AUDIT_LOG_QUERY_FAILED);
        return auditLogConverter.toVO(auditLog);
    }
}
