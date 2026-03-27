package com.schemaplexai.service.marketplace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.enums.PluginInstallStatus;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.PluginCatalogMapper;
import com.schemaplexai.dao.mapper.PluginInstallationMapper;
import com.schemaplexai.model.entity.PluginCatalog;
import com.schemaplexai.model.entity.PluginInstallation;
import com.schemaplexai.service.marketplace.compatibility.PluginCompatibilityChecker;
import com.schemaplexai.service.marketplace.ratelimit.PluginRateLimiter;
import com.schemaplexai.service.marketplace.security.PluginSecurityFilter;
import com.schemaplexai.service.marketplace.signature.PluginSignatureVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class PluginInstallOrchestrator {

    private static final String COMPATIBILITY_UNKNOWN = "unknown";
    private static final String COMPATIBILITY_COMPATIBLE = "compatible";
    private static final String COMPATIBILITY_INCOMPATIBLE = "incompatible";

    private final PluginCatalogMapper pluginCatalogMapper;
    private final PluginInstallationMapper pluginInstallationMapper;
    private final PluginSignatureVerifier pluginSignatureVerifier;
    private final PluginCompatibilityChecker pluginCompatibilityChecker;
    private final PluginSecurityFilter pluginSecurityFilter;
    private final PluginRateLimiter pluginRateLimiter;

    @Transactional(rollbackFor = Exception.class)
    public void install(String tenantId, String pluginCatalogId) {
        requireTenantId(tenantId);
        PluginCatalog catalog = requireCatalog(pluginCatalogId);
        PluginInstallation installation = loadOrCreateInstallation(tenantId, catalog);

        if (PluginInstallStatus.INSTALLED.getCode().equals(installation.getStatus())
                && StringUtils.hasText(installation.getInstalledVersion())
                && installation.getInstalledVersion().equals(catalog.getVersion())) {
            log.info("插件已安装且版本一致，跳过重复安装: tenantId={}, pluginUid={}, version={}",
                    tenantId, catalog.getPluginUid(), catalog.getVersion());
            return;
        }

        pluginRateLimiter.acquire(tenantId, "install:" + catalog.getPluginUid());
        pluginSecurityFilter.validatePluginCatalog(catalog);

        transition(installation, PluginInstallStatus.PENDING_VERIFY, false, COMPATIBILITY_UNKNOWN);

        if (!verifySignature(catalog)) {
            transition(installation, PluginInstallStatus.FAILED, false, COMPATIBILITY_UNKNOWN);
            throw new BusinessException(ResultCode.BAD_REQUEST, "插件签名校验失败，安装已终止");
        }
        transition(installation, PluginInstallStatus.VERIFIED, true, COMPATIBILITY_UNKNOWN);

        if (!checkCompatibility(catalog, tenantId)) {
            transition(installation, PluginInstallStatus.FAILED, true, COMPATIBILITY_INCOMPATIBLE);
            throw new BusinessException(ResultCode.BAD_REQUEST, "插件兼容性检查失败，安装已终止");
        }
        transition(installation, PluginInstallStatus.COMPATIBLE, true, COMPATIBILITY_COMPATIBLE);

        installation.setInstalledBy(SecurityUtil.getCurrentUserId());
        installation.setInstalledAt(LocalDateTime.now());
        transition(installation, PluginInstallStatus.INSTALLED, true, COMPATIBILITY_COMPATIBLE);

        log.info("插件安装成功: tenantId={}, pluginUid={}, status={}",
                tenantId, catalog.getPluginUid(), PluginInstallStatus.INSTALLED.getCode());
    }

    @Transactional(rollbackFor = Exception.class)
    public void uninstall(String tenantId, String pluginCatalogId) {
        requireTenantId(tenantId);
        PluginCatalog catalog = requireCatalog(pluginCatalogId);
        pluginRateLimiter.acquire(tenantId, "uninstall:" + catalog.getPluginUid());
        PluginInstallation installation = pluginInstallationMapper.selectOne(
                new LambdaQueryWrapper<PluginInstallation>()
                        .eq(PluginInstallation::getTenantId, tenantId)
                        .eq(PluginInstallation::getPluginUid, catalog.getPluginUid())
                        .last("LIMIT 1")
        );
        if (installation == null) {
            log.info("插件未安装，跳过卸载: tenantId={}, pluginUid={}", tenantId, catalog.getPluginUid());
            return;
        }
        transition(installation, PluginInstallStatus.UNINSTALLED,
                installation.getSignatureVerified(), installation.getCompatibilityStatus());
        log.info("插件卸载成功: tenantId={}, pluginUid={}", tenantId, catalog.getPluginUid());
    }

    private PluginCatalog requireCatalog(String pluginCatalogId) {
        PluginCatalog catalog = pluginCatalogMapper.selectById(pluginCatalogId);
        if (catalog == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "插件目录不存在");
        }
        if (!CommonConstant.STATUS_ACTIVE.equalsIgnoreCase(catalog.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "插件目录状态不可安装");
        }
        return catalog;
    }

    private PluginInstallation loadOrCreateInstallation(String tenantId, PluginCatalog catalog) {
        PluginInstallation installation = pluginInstallationMapper.selectOne(
                new LambdaQueryWrapper<PluginInstallation>()
                        .eq(PluginInstallation::getTenantId, tenantId)
                        .eq(PluginInstallation::getPluginUid, catalog.getPluginUid())
                        .last("LIMIT 1")
        );
        if (installation != null) {
            installation.setPluginCatalogId(catalog.getId());
            installation.setInstalledVersion(catalog.getVersion());
            pluginInstallationMapper.updateById(installation);
            return installation;
        }

        PluginInstallation created = new PluginInstallation();
        created.setTenantId(tenantId);
        created.setPluginUid(catalog.getPluginUid());
        created.setPluginCatalogId(catalog.getId());
        created.setInstalledVersion(catalog.getVersion());
        created.setStatus(PluginInstallStatus.PENDING_VERIFY.getCode());
        created.setSignatureVerified(false);
        created.setCompatibilityStatus(COMPATIBILITY_UNKNOWN);
        created.setInstalledBy(SecurityUtil.getCurrentUserId());
        created.setInstalledAt(LocalDateTime.now());
        try {
            pluginInstallationMapper.insert(created);
        } catch (DuplicateKeyException exception) {
            PluginInstallation existing = pluginInstallationMapper.selectOne(
                    new LambdaQueryWrapper<PluginInstallation>()
                            .eq(PluginInstallation::getTenantId, tenantId)
                            .eq(PluginInstallation::getPluginUid, catalog.getPluginUid())
                            .last("LIMIT 1")
            );
            if (existing == null) {
                throw exception;
            }
            return existing;
        }
        return created;
    }

    private void transition(PluginInstallation installation, PluginInstallStatus status,
                            Boolean signatureVerified, String compatibilityStatus) {
        PluginInstallation update = new PluginInstallation();
        update.setId(installation.getId());
        update.setStatus(status.getCode());
        update.setPluginCatalogId(installation.getPluginCatalogId());
        update.setInstalledVersion(installation.getInstalledVersion());
        update.setSignatureVerified(signatureVerified);
        update.setCompatibilityStatus(compatibilityStatus);
        if (StringUtils.hasText(installation.getInstalledBy())) {
            update.setInstalledBy(installation.getInstalledBy());
        }
        if (installation.getInstalledAt() != null) {
            update.setInstalledAt(installation.getInstalledAt());
        }
        pluginInstallationMapper.updateById(update);

        installation.setStatus(status.getCode());
        installation.setSignatureVerified(signatureVerified);
        installation.setCompatibilityStatus(compatibilityStatus);
    }

    private boolean verifySignature(PluginCatalog catalog) {
        return pluginSignatureVerifier.verify(catalog);
    }

    private boolean checkCompatibility(PluginCatalog catalog, String tenantId) {
        return pluginCompatibilityChecker.check(catalog, tenantId);
    }

    private void requireTenantId(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "缺少租户上下文");
        }
    }
}
