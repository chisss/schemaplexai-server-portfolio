package com.schemaplexai.service.integration.merge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.MergeRequestStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.BranchRuleMapper;
import com.schemaplexai.dao.mapper.IntegrationMapper;
import com.schemaplexai.dao.mapper.IntegrationProjectMapper;
import com.schemaplexai.model.entity.BranchRule;
import com.schemaplexai.model.entity.Integration;
import com.schemaplexai.model.entity.IntegrationProject;
import com.schemaplexai.service.integration.platform.GitPlatformAdapter;
import com.schemaplexai.service.integration.platform.GitPlatformAdapterFactory;
import com.schemaplexai.service.integration.platform.RemoteMergeRequest;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 合并请求服务
 * 负责创建和管理Git合并请求（MR/PR）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MergeRequestService {

    private final IntegrationProjectMapper integrationProjectMapper;
    private final IntegrationMapper integrationMapper;
    private final BranchRuleMapper branchRuleMapper;
    private final GitPlatformAdapterFactory gitPlatformAdapterFactory;

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
        String mrTitle = StringUtils.hasText(title) ? title : "Agent Auto Merge Request";

        // 查找关联的集成配置
        Integration integration = integrationMapper.selectById(integrationProject.getIntegrationId());
        if (integration == null) {
            throw new BusinessException(ResultCode.INTEGRATION_NOT_FOUND);
        }

        GitPlatformAdapter adapter = gitPlatformAdapterFactory.getAdapter(integration.getPlatform());
        RemoteMergeRequest remoteMr = adapter.createMergeRequest(
                integration.getConfig(),
                integrationProject.getExternalProjectName(),
                sourceBranch, targetBranch,
                mrTitle, description);

        log.info("创建合并请求成功: remoteMrId={}, integrationProjectId={}, source={}, target={}, webUrl={}",
                remoteMr.getRemoteMrId(), integrationProjectId, sourceBranch, targetBranch, remoteMr.getWebUrl());

        return MergeRequestTicket.builder()
                .requestId(remoteMr.getRemoteMrId())
                .integrationProjectId(integrationProjectId)
                .sourceBranch(sourceBranch)
                .targetBranch(targetBranch)
                .title(mrTitle)
                .description(description)
                .webUrl(remoteMr.getWebUrl())
                .status(MergeRequestStatusEnum.OPENED)
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
        MergeRequestStatusEnum mrStatus = MergeRequestStatusEnum.fromCode(status);
        log.info("处理合并请求Webhook: requestId={}, status={}", requestId, mrStatus.getCode());
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
        private String webUrl;
        private MergeRequestStatusEnum status;
        private LocalDateTime createdAt;
    }
}
