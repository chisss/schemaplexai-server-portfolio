package com.schemaplexai.service.monitor;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.monitor.AuditLogQueryRequest;
import com.schemaplexai.model.vo.monitor.AuditLogVO;

/**
 * 审计日志服务
 */
public interface AuditLogService {

    PageResult<AuditLogVO> page(AuditLogQueryRequest request);

    AuditLogVO getById(String id);
}
