package com.schemaplexai.web.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.schemaplexai.common.util.SecurityUtil;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * MyBatis-Plus 配置
 */
@Configuration
public class MyBatisPlusConfig {

    /** 不做租户隔离的表（无 tenant_id 列或通过父表关联实现隔离） */
    private static final List<String> IGNORE_TENANT_TABLES = Arrays.asList(
            // 系统级/租户管理表
            "sf_tenant",
            "sf_audit_log",
            "sf_i18n_locale",
            "sf_i18n_message",
            // 权限体系表（系统级，不区分租户）
            "sf_permission",
            "sf_role",
            "sf_user_role",
            "sf_role_permission",
            // 菜单为系统级全局菜单（tenant_id=null），不做租户过滤
            "sf_menu",
            // Agent 子表（通过 agent_id 关联父表，父表已做租户隔离）
            "sf_agent_config",
            "sf_agent_team_member",
            "sf_agent_context_binding",
            "sf_steering_document",
            // Spec 子表（通过 spec_id 关联父表）
            "sf_spec_document",
            "sf_spec_version",
            // 上下文子表
            "sf_context_item",
            // 工作流子表
            "sf_workflow_node_execution",
            // 评审子表
            "sf_review_comment",
            // 系统级字典表（全局共享，不做租户隔离）
            "sf_dict",
            "sf_dict_item",
            // 租户运行时策略由系统管理员统一管理
            "sf_tenant_runtime_policy",
            // 插件目录为系统级聚合目录（tenant_id 可为空）
            "sf_plugin_catalog",
            // 模型组子表（通过 group_id 关联父表，父表已做租户隔离）
            "sf_ai_model_group_item",
            "sf_builtin_tool"
    );

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 租户拦截器（必须在分页拦截器之前）
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                String tenantId = SecurityUtil.getCurrentTenantId();
                return new StringValue(tenantId != null ? tenantId : "");
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                // 没有租户上下文时（如登录等未认证场景），跳过所有表的租户过滤
                if (SecurityUtil.getCurrentTenantId() == null) {
                    return true;
                }
                return IGNORE_TENANT_TABLES.contains(tableName);
            }
        }));

        // 分页拦截器
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));

        return interceptor;
    }

    /**
     * 自动填充处理器 - 创建/更新时自动填充公共字段
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime::now, LocalDateTime.class);
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime::now, LocalDateTime.class);
                String userId = SecurityUtil.getCurrentUserId();
                if (userId != null) {
                    this.strictInsertFill(metaObject, "createdBy", () -> userId, String.class);
                    this.strictInsertFill(metaObject, "updatedBy", () -> userId, String.class);
                }
                String tenantId = SecurityUtil.getCurrentTenantId();
                if (tenantId != null) {
                    this.strictInsertFill(metaObject, "tenantId", () -> tenantId, String.class);
                }
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime::now, LocalDateTime.class);
                String userId = SecurityUtil.getCurrentUserId();
                if (userId != null) {
                    this.strictUpdateFill(metaObject, "updatedBy", () -> userId, String.class);
                }
            }
        };
    }
}
