package com.schemaplexai.service.common;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseConstraintTranslatorTest {

    @Test
    void shouldTranslateTenantUsernameConstraint() {
        BusinessException exception = DatabaseConstraintTranslator.translateOrDefault(
                new DuplicateKeyException("duplicate key value violates unique constraint \"uk_user_tenant_username_active\""),
                ResultCode.FAIL,
                "兜底错误"
        );

        assertThat(exception.getCode()).isEqualTo(ResultCode.USER_ALREADY_EXISTS.getCode());
        assertThat(exception).hasMessage("当前租户下用户名已存在");
    }

    @Test
    void shouldTranslateTenantRoleCodeConstraint() {
        BusinessException exception = DatabaseConstraintTranslator.translateOrDefault(
                new DuplicateKeyException("duplicate key value violates unique constraint \"uk_role_tenant_code_active\""),
                ResultCode.FAIL,
                "兜底错误"
        );

        assertThat(exception.getCode()).isEqualTo(ResultCode.ROLE_CODE_DUPLICATE.getCode());
        assertThat(exception).hasMessage("当前租户下角色编码已存在");
    }

    @Test
    void shouldUseFallbackWhenConstraintUnknown() {
        BusinessException exception = DatabaseConstraintTranslator.translateOrDefault(
                new DuplicateKeyException("unknown constraint"),
                ResultCode.BAD_REQUEST,
                "未知唯一约束冲突"
        );

        assertThat(exception.getCode()).isEqualTo(ResultCode.BAD_REQUEST.getCode());
        assertThat(exception).hasMessage("未知唯一约束冲突");
    }
}
