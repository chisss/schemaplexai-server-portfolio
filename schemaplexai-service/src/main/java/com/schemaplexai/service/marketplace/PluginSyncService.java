package com.schemaplexai.service.marketplace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.dao.mapper.PluginCatalogMapper;
import com.schemaplexai.model.entity.PluginCatalog;
import com.schemaplexai.service.marketplace.adapter.PluginVendorAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PluginSyncService {

    private final List<PluginVendorAdapter> vendorAdapters;
    private final PluginCatalogMapper pluginCatalogMapper;

    @Async
    @Scheduled(cron = "${marketplace.plugin.sync.cron:0 */15 * * * ?}")
    public void syncAll() {
        if (CollectionUtils.isEmpty(vendorAdapters)) {
            log.warn("未发现任何插件厂商适配器，跳过同步");
            return;
        }
        for (PluginVendorAdapter adapter : vendorAdapters) {
            try {
                syncVendor(adapter);
            } catch (Exception exception) {
                log.error("插件目录同步失败: vendor={}", adapter.vendor(), exception);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected void syncVendor(PluginVendorAdapter adapter) {
        List<PluginCatalog> fetched = adapter.fetchPluginList();
        if (CollectionUtils.isEmpty(fetched)) {
            log.info("插件目录为空: vendor={}", adapter.vendor());
            return;
        }
        int count = 0;
        for (PluginCatalog item : fetched) {
            if (item == null || !StringUtils.hasText(item.getPluginUid())) {
                continue;
            }
            upsertCatalog(adapter.vendor(), item);
            count++;
        }
        log.info("插件目录同步完成: vendor={}, count={}", adapter.vendor(), count);
    }

    private void upsertCatalog(String vendor, PluginCatalog incoming) {
        PluginCatalog existing = pluginCatalogMapper.selectOne(
                new LambdaQueryWrapper<PluginCatalog>()
                        .eq(PluginCatalog::getVendor, vendor)
                        .eq(PluginCatalog::getPluginUid, incoming.getPluginUid())
                        .eq(PluginCatalog::getVersion, incoming.getVersion())
                        .last("LIMIT 1")
        );

        if (existing == null) {
            PluginCatalog created = new PluginCatalog();
            fillCatalog(created, vendor, incoming);
            try {
                pluginCatalogMapper.insert(created);
                return;
            } catch (DuplicateKeyException exception) {
                log.warn("插件目录并发写入冲突，降级为更新: vendor={}, pluginUid={}, version={}",
                        vendor, incoming.getPluginUid(), incoming.getVersion());
                existing = pluginCatalogMapper.selectOne(
                        new LambdaQueryWrapper<PluginCatalog>()
                                .eq(PluginCatalog::getVendor, vendor)
                                .eq(PluginCatalog::getPluginUid, incoming.getPluginUid())
                                .eq(PluginCatalog::getVersion, incoming.getVersion())
                                .last("LIMIT 1")
                );
                if (existing == null) {
                    throw exception;
                }
            }
        }

        PluginCatalog update = new PluginCatalog();
        update.setId(existing.getId());
        fillCatalog(update, vendor, incoming);
        pluginCatalogMapper.updateById(update);
    }

    private void fillCatalog(PluginCatalog target, String vendor, PluginCatalog source) {
        target.setTenantId(null);
        target.setVendor(vendor);
        target.setPluginUid(source.getPluginUid());
        target.setName(source.getName());
        target.setDisplayName(source.getDisplayName());
        target.setDescription(source.getDescription());
        target.setPluginType(source.getPluginType());
        target.setVersion(source.getVersion());
        target.setSourceUrl(source.getSourceUrl());
        target.setIconUrl(source.getIconUrl());
        target.setSignature(source.getSignature());
        target.setManifest(source.getManifest());
        target.setStatus(StringUtils.hasText(source.getStatus()) ? source.getStatus() : CommonConstant.STATUS_ACTIVE);
    }
}
