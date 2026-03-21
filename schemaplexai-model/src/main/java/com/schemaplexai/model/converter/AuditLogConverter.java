package com.schemaplexai.model.converter;

import com.schemaplexai.model.entity.AuditLog;
import com.schemaplexai.model.vo.monitor.AuditLogVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import java.util.List;

/**
 * 审计日志转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface AuditLogConverter {

    AuditLogVO toVO(AuditLog entity);
    List<AuditLogVO> toVOList(List<AuditLog> entities);
}
