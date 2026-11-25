package com.schemaplexai.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.TenantMapper;
import com.schemaplexai.model.entity.Tenant;
import com.schemaplexai.service.workflow.ReviewSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审批超时处理定时任务
 * 定期检查已超过截止时间的评审会话，按超时策略（auto_pass/escalate/remind）分别处理
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalTimeoutTask {

    private final ReviewSessionService reviewSessionService;
    private final TenantMapper tenantMapper;

    /**
     * 每30分钟检查超时的审批请求
     * 时间间隔后续改为可配置
     */
    // TODO: fixedRate 改为可配置（如 @Value + fixedRateString）
    @Scheduled(fixedRate = 1800000)
    public void handleApprovalTimeout() {
        log.debug("开始检查审批超时...");

        var tenants = tenantMapper.selectList(new LambdaQueryWrapper<Tenant>()
                .select(Tenant::getId)
                .eq(Tenant::getStatus, CommonConstant.STATUS_ACTIVE));
        String previousTenantId = SecurityUtil.getCurrentTenantId();
        for (Tenant tenant : tenants) {
            SecurityUtil.setCurrentTenantId(tenant.getId());
            try {
                reviewSessionService.handleTimeoutSessions();
            } catch (Exception e) {
                log.error("审批超时处理异常: tenantId={}", tenant.getId(), e);
            } finally {
                SecurityUtil.setCurrentTenantId(previousTenantId);
            }
        }
        log.debug("审批超时检查完成");
    }
}
