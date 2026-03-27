package com.schemaplexai.service.integration.merge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.BranchRuleMapper;
import com.schemaplexai.dao.mapper.IntegrationProjectMapper;
import com.schemaplexai.model.entity.BranchRule;
import com.schemaplexai.model.entity.IntegrationProject;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 合并请求服务
 * 负责创建和管理Git合并请求（MR/PR）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MergeRequestService {

    private final IntegrationProjectMapper integrationProjectMapper;
    private final BranchRuleMapper branchRuleMapper;

    /**
     * 创建合并请求
     *
     * @param tenantId            租户ID
     * @param integrationProjectId 集成项目ID
     * @param sourceBranch       源分支
     * @param title              MR标题
     * @param description        MR描述
     * @return 合并请求票据
     */
    public MergeRequestTicket createMergeRequest(String tenantId,
                                                 String integrationProjectId,
                                                 String sourceBranch,
                                                 String title,
                                                 String description) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(integrationProjectId) || !StringUtils.hasText(sourceBranch)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "创建合并请求参数不完整");
        }
        IntegrationProject integrationProject = integrationProjectMapper.selectOne(
                new LambdaQueryWrapper<IntegrationProject>()
                        .eq(IntegrationProject::getId, integrationProjectId)
                        .eq(IntegrationProject::getTenantId, tenantId)
                        .last("LIMIT 1"));
        if (integrationProject == null) {
            throw new BusinessException(ResultCode.INTEGRATION_PROJECT_NOT_FOUND);
        }

        String targetBranch = resolveTargetBranch(tenantId, integrationProjectId);
        String requestId = "mr-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        log.info("创建合并请求: requestId={}, integrationProjectId={}, source={}, target={}, title={}",
                requestId, integrationProjectId, sourceBranch, targetBranch, title);

        // TODO: 接入具体Git平台API（GitLab/GitHub）

        return MergeRequestTicket.builder()
                .requestId(requestId)
                .integrationProjectId(integrationProjectId)
                .sourceBranch(sourceBranch)
                .targetBranch(targetBranch)
                .title(StringUtils.hasText(title) ? title : "Agent Auto Merge Request")
                .description(description)
                .status(MrStatus.OPENED)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * 处理Webhook回调（合并/关闭）
     */
    public void handleWebhook(String requestId, String status) {
        if (!StringUtils.hasText(requestId)) {
            return;
        }
        log.info("处理合并请求Webhook: requestId={}, status={}", requestId, status);
        // TODO: 更新MR状态
    }

    /**
     * 标记MR为已合并
     */
    public boolean markMerged(String requestId) {
        if (!StringUtils.hasText(requestId)) {
            return false;
        }
        log.info("标记合并请求为已合并: requestId={}", requestId);
        return true;
    }

    /**
     * 解析目标分支（根据BranchRule）
     */
    private String resolveTargetBranch(String tenantId, String integrationProjectId) {
        if (!StringUtils.hasText(tenantId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "缺少租户上下文");
        }
        BranchRule rule = branchRuleMapper.selectOne(new LambdaQueryWrapper<BranchRule>()
                .eq(BranchRule::getTenantId, tenantId)
                .eq(BranchRule::getIntegrationProjectId, integrationProjectId)
                .last("LIMIT 1"));
        if (rule == null) {
            return "main";
        }
        if (StringUtils.hasText(rule.getBranchPattern())) {
            return rule.getBranchPattern();
        }
        return "main";
    }

    @Data
    @Builder
    public static class MergeRequestTicket {
        private String requestId;
        private String integrationProjectId;
        private String sourceBranch;
        private String targetBranch;
        private String title;
        private String description;
        private MrStatus status;
        private LocalDateTime createdAt;
    }

    public enum MrStatus {
        OPENED,
        MERGED,
        CLOSED
    }
}
