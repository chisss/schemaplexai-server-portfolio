package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.AuditLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * AuditLog Mapper接口
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
    @Insert("""
            INSERT INTO sf_audit_log (id, tenant_id, user_id, username, action, resource, resource_id, detail, created_at)
            VALUES (CAST(#{id} AS uuid), CAST(#{tenantId} AS uuid), CAST(#{userId} AS uuid),
                    #{username}, #{action}, #{resource}, CAST(#{resourceId} AS uuid), #{detail}, #{createdAt})
            """)
    int insertSemanticQueryAudit(AuditLog log);
}
