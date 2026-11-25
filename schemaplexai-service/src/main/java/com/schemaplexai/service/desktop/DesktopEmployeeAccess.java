package com.schemaplexai.service.desktop;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DesktopEmployeeAccess {

    private final JdbcTemplate jdbc;

    public record Employee(String tenantId, String userId) {
        public static Employee current() {
            String tenant = SecurityUtil.getCurrentTenantId();
            String user = SecurityUtil.getCurrentUserId();
            if (!StringUtils.hasText(tenant) || !StringUtils.hasText(user)) {
                throw new BusinessException(ResultCode.UNAUTHORIZED);
            }
            return new Employee(tenant, user);
        }
    }

    public boolean granted(Employee employee, String type, String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM sf_desktop_resource_grant
                    WHERE tenant_id = ? AND user_id = ? AND resource_type = ? AND resource_id = ?)
                """, Boolean.class, employee.tenantId(), employee.userId(), type, id));
    }

    public void grant(Employee employee, String type, String id, String userId) {
        jdbc.update("""
                INSERT INTO sf_desktop_resource_grant(tenant_id,user_id,resource_type,resource_id)
                VALUES(?,?,?,?) ON CONFLICT DO NOTHING
                """, employee.tenantId(), userId, type, id);
    }

    public void revoke(Employee employee, String type, String id, String userId) {
        jdbc.update("""
                DELETE FROM sf_desktop_resource_grant
                WHERE tenant_id=? AND user_id=? AND resource_type=? AND resource_id=?
                """, employee.tenantId(), userId, type, id);
    }

    public void requireGrant(String type, String id) {
        if (!granted(Employee.current(), type, id)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "当前用户未获此资源授权");
        }
    }
}
