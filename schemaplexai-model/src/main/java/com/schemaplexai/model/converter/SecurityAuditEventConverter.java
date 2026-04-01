package com.schemaplexai.model.converter;

import com.schemaplexai.model.entity.SecurityAuditEvent;
import com.schemaplexai.model.vo.security.SecurityAuditEventVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 安全审计事件转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface SecurityAuditEventConverter {

    SecurityAuditEventVO toVO(SecurityAuditEvent entity);

    List<SecurityAuditEventVO> toVOList(List<SecurityAuditEvent> entities);
}
