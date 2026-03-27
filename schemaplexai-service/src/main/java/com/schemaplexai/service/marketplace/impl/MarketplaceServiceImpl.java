package com.schemaplexai.service.marketplace.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.McpServerMapper;
import com.schemaplexai.dao.mapper.PluginCatalogMapper;
import com.schemaplexai.dao.mapper.PluginInstallationMapper;
import com.schemaplexai.dao.mapper.SkillInstallationMapper;
import com.schemaplexai.dao.mapper.SkillMapper;
import com.schemaplexai.common.enums.PluginInstallStatus;
import com.schemaplexai.model.dto.marketplace.MarketplacePluginQueryRequest;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.model.entity.PluginCatalog;
import com.schemaplexai.model.entity.PluginInstallation;
import com.schemaplexai.model.entity.Skill;
import com.schemaplexai.model.entity.SkillInstallation;
import com.schemaplexai.model.vo.marketplace.MarketplacePluginVO;
import com.schemaplexai.service.marketplace.MarketplaceService;
import com.schemaplexai.service.marketplace.PluginInstallOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件市场服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketplaceServiceImpl implements MarketplaceService {

    private static final String SOURCE_SKILL = "skill";
    private static final String SOURCE_MCP = "mcp";

    private final SkillMapper skillMapper;
    private final McpServerMapper mcpServerMapper;
    private final SkillInstallationMapper skillInstallationMapper;
    private final PluginCatalogMapper pluginCatalogMapper;
    private final PluginInstallOrchestrator pluginInstallOrchestrator;
    private final PluginInstallationMapper pluginInstallationMapper;

    @Override
    public PageResult<MarketplacePluginVO> listPlugins(MarketplacePluginQueryRequest request) {
        String tenantId = requireTenantId();
        MarketplacePluginQueryRequest query = request == null ? new MarketplacePluginQueryRequest() : request;

        List<MarketplacePluginVO> all = new ArrayList<>();
        Map<String, PluginInstallation> installedPluginMap = loadInstalledPluginMap(tenantId);

        var catalogWrapper = new LambdaQueryWrapper<PluginCatalog>();
        catalogWrapper.eq(PluginCatalog::getStatus, CommonConstant.STATUS_ACTIVE).orderByDesc(PluginCatalog::getCreatedAt);
        if (StringUtils.hasText(query.getKeyword())) {
            catalogWrapper.and(w -> w.like(PluginCatalog::getName, query.getKeyword())
                    .or().like(PluginCatalog::getDisplayName, query.getKeyword())
                    .or().like(PluginCatalog::getDescription, query.getKeyword()));
        }
        if (StringUtils.hasText(query.getSourceType())) {
            catalogWrapper.eq(PluginCatalog::getPluginType, query.getSourceType());
        }
        List<PluginCatalog> catalogs = pluginCatalogMapper.selectList(catalogWrapper);
        for (PluginCatalog catalog : catalogs) {
            PluginInstallation installation = installedPluginMap.get(catalog.getPluginUid());
            all.add(buildCatalogVO(catalog, installation));
        }

        Map<String, SkillInstallation> installedSkillMap = loadInstalledSkillMap(tenantId);

        if (!SOURCE_MCP.equals(query.getSourceType())) {
            var skillWrapper = new LambdaQueryWrapper<Skill>();
            skillWrapper.orderByDesc(Skill::getCreatedAt);
            if (StringUtils.hasText(query.getKeyword())) {
                skillWrapper.and(w -> w.like(Skill::getName, query.getKeyword())
                        .or().like(Skill::getDisplayName, query.getKeyword())
                        .or().like(Skill::getDescription, query.getKeyword()));
            }
            List<Skill> skills = skillMapper.selectList(skillWrapper);
            for (Skill skill : skills) {
                SkillInstallation installation = installedSkillMap.get(skill.getId());
                MarketplacePluginVO vo = new MarketplacePluginVO();
                vo.setId(skill.getId());
                vo.setName(StringUtils.hasText(skill.getDisplayName()) ? skill.getDisplayName() : skill.getName());
                vo.setDescription(skill.getDescription());
                vo.setCategory(skill.getCategory());
                vo.setSourceType(SOURCE_SKILL);
                vo.setStatus(skill.getStatus());
                vo.setInstalled(installation != null);
                vo.setInstalledVersion(installation != null ? installation.getInstalledVersion() : null);
                all.add(vo);
            }
        }

        if (!SOURCE_SKILL.equals(query.getSourceType())) {
            var mcpWrapper = new LambdaQueryWrapper<McpServer>();
            mcpWrapper.eq(McpServer::getTenantId, tenantId).orderByDesc(McpServer::getCreatedAt);
            if (StringUtils.hasText(query.getKeyword())) {
                mcpWrapper.and(w -> w.like(McpServer::getName, query.getKeyword())
                        .or().like(McpServer::getUrl, query.getKeyword()));
            }
            List<McpServer> mcpServers = mcpServerMapper.selectList(mcpWrapper);
            for (McpServer server : mcpServers) {
                MarketplacePluginVO vo = new MarketplacePluginVO();
                vo.setId(server.getId());
                vo.setName(server.getName());
                vo.setDescription(server.getUrl());
                vo.setCategory("mcp");
                vo.setSourceType(SOURCE_MCP);
                vo.setStatus(server.getStatus());
                vo.setInstalled(!CommonConstant.STATUS_INACTIVE.equals(server.getStatus()));
                vo.setInstalledVersion(null);
                all.add(vo);
            }
        }

        if (query.getInstalled() != null) {
            all.removeIf(item -> !query.getInstalled().equals(item.getInstalled()));
        }

        int page = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int size = query.getSize() == null || query.getSize() < 1 ? 20 : query.getSize();
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        List<MarketplacePluginVO> paged = all.subList(from, to);

        return new PageResult<>(paged, all.size(), page, size);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void installPlugin(String pluginId) {
        String tenantId = requireTenantId();

        PluginCatalog pluginCatalog = pluginCatalogMapper.selectById(pluginId);
        if (pluginCatalog != null) {
            pluginInstallOrchestrator.install(tenantId, pluginId);
            log.info("安装聚合插件成功: pluginId={}, vendor={}", pluginId, pluginCatalog.getVendor());
            return;
        }

        Skill skill = skillMapper.selectById(pluginId);
        if (skill != null) {
            upsertSkillInstallation(skill);
            log.info("安装 Skill 插件成功: pluginId={}", pluginId);
            return;
        }

        McpServer mcpServer = mcpServerMapper.selectOne(new LambdaQueryWrapper<McpServer>()
                .eq(McpServer::getId, pluginId)
                .eq(McpServer::getTenantId, tenantId)
                .last("LIMIT 1"));
        if (mcpServer != null) {
            McpServer update = new McpServer();
            update.setId(pluginId);
            update.setStatus(CommonConstant.STATUS_ACTIVE);
            mcpServerMapper.updateById(update);
            log.info("启用 MCP 插件成功: pluginId={}", pluginId);
            return;
        }

        throw new BusinessException(ResultCode.BAD_REQUEST, "插件不存在");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void uninstallPlugin(String pluginId) {
        String tenantId = requireTenantId();

        PluginCatalog pluginCatalog = pluginCatalogMapper.selectById(pluginId);
        if (pluginCatalog != null) {
            pluginInstallOrchestrator.uninstall(tenantId, pluginId);
            log.info("卸载聚合插件成功: pluginId={}, vendor={}", pluginId, pluginCatalog.getVendor());
            return;
        }

        Skill skill = skillMapper.selectById(pluginId);
        if (skill != null) {
            skillInstallationMapper.delete(new LambdaQueryWrapper<SkillInstallation>()
                    .eq(SkillInstallation::getTenantId, tenantId)
                    .eq(SkillInstallation::getSkillId, pluginId));
            log.info("卸载 Skill 插件成功: pluginId={}", pluginId);
            return;
        }

        McpServer mcpServer = mcpServerMapper.selectOne(new LambdaQueryWrapper<McpServer>()
                .eq(McpServer::getId, pluginId)
                .eq(McpServer::getTenantId, tenantId)
                .last("LIMIT 1"));
        if (mcpServer != null) {
            McpServer update = new McpServer();
            update.setId(pluginId);
            update.setStatus(CommonConstant.STATUS_INACTIVE);
            mcpServerMapper.updateById(update);
            log.info("停用 MCP 插件成功: pluginId={}", pluginId);
            return;
        }

        throw new BusinessException(ResultCode.BAD_REQUEST, "插件不存在");
    }

    private Map<String, SkillInstallation> loadInstalledSkillMap(String tenantId) {
        List<SkillInstallation> installations = skillInstallationMapper.selectList(
                new LambdaQueryWrapper<SkillInstallation>()
                        .eq(SkillInstallation::getTenantId, tenantId)
                        .eq(SkillInstallation::getStatus, "installed")
        );
        Map<String, SkillInstallation> map = new HashMap<>();
        for (SkillInstallation installation : installations) {
            map.put(installation.getSkillId(), installation);
        }
        return map;
    }

    private void upsertSkillInstallation(Skill skill) {
        String tenantId = requireTenantId();
        SkillInstallation installation = skillInstallationMapper.selectOne(
                new LambdaQueryWrapper<SkillInstallation>()
                        .eq(SkillInstallation::getTenantId, tenantId)
                        .eq(SkillInstallation::getSkillId, skill.getId())
                        .last("LIMIT 1")
        );

        if (installation == null) {
            installation = new SkillInstallation();
            installation.setTenantId(tenantId);
            installation.setSkillId(skill.getId());
            installation.setInstalledVersion(StringUtils.hasText(skill.getVersion()) ? skill.getVersion() : "1.0.0");
            installation.setStatus("installed");
            installation.setInstalledBy(SecurityUtil.getCurrentUserId());
            installation.setInstalledAt(LocalDateTime.now());
            skillInstallationMapper.insert(installation);
            return;
        }

        SkillInstallation update = new SkillInstallation();
        update.setId(installation.getId());
        update.setInstalledVersion(StringUtils.hasText(skill.getVersion()) ? skill.getVersion() : installation.getInstalledVersion());
        update.setStatus("installed");
        update.setInstalledBy(SecurityUtil.getCurrentUserId());
        update.setInstalledAt(LocalDateTime.now());
        skillInstallationMapper.updateById(update);
    }

    private Map<String, PluginInstallation> loadInstalledPluginMap(String tenantId) {
        List<PluginInstallation> installations = pluginInstallationMapper.selectList(
                new LambdaQueryWrapper<PluginInstallation>()
                        .eq(PluginInstallation::getTenantId, tenantId)
                        .eq(PluginInstallation::getStatus, PluginInstallStatus.INSTALLED.getCode())
        );
        Map<String, PluginInstallation> map = new HashMap<>();
        for (PluginInstallation installation : installations) {
            map.put(installation.getPluginUid(), installation);
        }
        return map;
    }

    private MarketplacePluginVO buildCatalogVO(PluginCatalog catalog, PluginInstallation installation) {
        MarketplacePluginVO vo = new MarketplacePluginVO();
        vo.setId(catalog.getId());
        vo.setName(StringUtils.hasText(catalog.getDisplayName()) ? catalog.getDisplayName() : catalog.getName());
        vo.setDescription(catalog.getDescription());
        vo.setCategory(catalog.getVendor());
        vo.setSourceType(catalog.getPluginType());
        vo.setStatus(catalog.getStatus());
        vo.setInstalled(installation != null);
        vo.setInstalledVersion(installation != null ? installation.getInstalledVersion() : null);
        return vo;
    }

    private String requireTenantId() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (!StringUtils.hasText(tenantId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "缺少租户上下文");
        }
        return tenantId;
    }
}
