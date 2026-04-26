package com.schemaplexai.web.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.TenantMapper;
import com.schemaplexai.model.entity.Tenant;
import com.schemaplexai.service.agent.system.SystemAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 系统Agent初始化器 — 启动时为每个租户确保存在默认系统Agent
 */
@Slf4j
@Component
@Order(200)
@RequiredArgsConstructor
public class SystemAgentInitializer implements CommandLineRunner {

    private final TenantMapper tenantMapper;
    private final SystemAgentService systemAgentService;

    @Override
    public void run(String... args) {
        log.info("===== 开始初始化系统Agent =====");
        List<Tenant> tenants = tenantMapper.selectList(
                new LambdaQueryWrapper<Tenant>().eq(Tenant::getStatus, "active"));
        int created = 0;
        for (Tenant tenant : tenants) {
            try {
                if (systemAgentService.findSystemAgent(tenant.getId()) == null) {
                    systemAgentService.createSystemAgent(tenant.getId());
                    created++;
                }
            } catch (Exception e) {
                log.warn("租户[{}]系统Agent初始化失败: {}", tenant.getId(), e.getMessage());
            }
        }
        log.info("===== 系统Agent初始化完成: 共{}个租户, 新建{}个 =====", tenants.size(), created);
    }
}
