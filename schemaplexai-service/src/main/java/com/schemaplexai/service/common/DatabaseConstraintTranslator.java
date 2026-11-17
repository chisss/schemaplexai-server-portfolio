package com.schemaplexai.service.common;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import org.springframework.util.StringUtils;

/**
 * 数据库唯一约束错误转译器
 */
public final class DatabaseConstraintTranslator {

    private DatabaseConstraintTranslator() {}

    public static BusinessException translateOrDefault(Throwable exception, ResultCode fallbackCode, String fallbackMessage) {
        String message = collectMessages(exception);
        if (message.contains("uk_user_tenant_username_active")) {
            return new BusinessException(ResultCode.USER_ALREADY_EXISTS, "当前租户下用户名已存在");
        }
        if (message.contains("uk_user_tenant_email_active")) {
            return new BusinessException(ResultCode.USER_ALREADY_EXISTS, "当前租户下邮箱已存在");
        }
        if (message.contains("uk_role_tenant_code_active")) {
            return new BusinessException(ResultCode.ROLE_CODE_DUPLICATE, "当前租户下角色编码已存在");
        }
        if (message.contains("uk_budget_scope_cycle_active")) {
            return new BusinessException(ResultCode.BUDGET_CYCLE_CONFLICT, "当前租户下同一范围和周期已存在活跃预算");
        }
        return new BusinessException(fallbackCode, fallbackMessage);
    }

    private static String collectMessages(Throwable exception) {
        StringBuilder builder = new StringBuilder();
        Throwable current = exception;
        while (current != null) {
            if (StringUtils.hasText(current.getMessage())) {
                builder.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return builder.toString();
    }
}
