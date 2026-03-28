package com.schemaplexai.service.context.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.ContextRelationMapper;
import com.schemaplexai.model.entity.ContextRelation;
import com.schemaplexai.model.vo.context.ContextRelationVO;
import com.schemaplexai.dao.mapper.ContextEntityMapper;
import com.schemaplexai.dao.mapper.ContextItemMapper;
import com.schemaplexai.dao.mapper.ContextSnapshotMapper;
import com.schemaplexai.model.converter.ContextEntityConverter;
import com.schemaplexai.model.converter.ContextItemConverter;
import com.schemaplexai.model.dto.context.*;
import com.schemaplexai.model.entity.ContextEntity;
import com.schemaplexai.model.entity.ContextItem;
import com.schemaplexai.model.entity.ContextSnapshot;
import com.schemaplexai.model.vo.context.*;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.context.ContextService;
import com.schemaplexai.service.context.handler.ContextItemHandler;
import com.schemaplexai.service.context.validator.ContextValidator;
import com.schemaplexai.service.memory.rag.DocumentIngestionService;
import com.schemaplexai.service.vector.MilvusVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文管理服务实现
 * 编排器模式：委托 Validator、Handler、Converter 完成具体逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextServiceImpl implements ContextService {

    private final ContextEntityMapper contextEntityMapper;
    private final ContextItemMapper contextItemMapper;
    private final ContextSnapshotMapper contextSnapshotMapper;
    private final ContextEntityConverter contextEntityConverter;
    private final ContextItemConverter contextItemConverter;
    private final ContextValidator contextValidator;
    private final ContextItemHandler contextItemHandler;
    private final EntityValidator entityValidator;
    private final ContextRelationMapper contextRelationMapper;

    /** Milvus 可选注入（Milvus 未启动时为 null） */
    @Lazy
    @Autowired(required = false)
    private MilvusVectorService milvusVectorService;

    /** RAG 文档摄入服务（Milvus 未启动时为 null） */
    @Lazy
    @Autowired(required = false)
    private DocumentIngestionService documentIngestionService;

    // ===== 上下文 CRUD =====

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ContextVO create(ContextCreateRequest request) {
        contextValidator.validateContextLevel(request.getContextLevel());
        contextValidator.validateProjectRequired(request.getContextLevel(), request.getProjectId());
        contextValidator.validateNameUnique(request.getName());

        var entity = contextEntityConverter.fromCreateRequest(request);
        contextEntityMapper.insert(entity);

        log.info("创建上下文成功: contextId={}, name={}, level={}",
                entity.getId(), entity.getName(), entity.getContextLevel());

        // 批量保存关联关系（建立知识图谱连线）
        if (request.getLinkedContextIds() != null && !request.getLinkedContextIds().isEmpty()) {
            request.getLinkedContextIds().stream()
                    .filter(targetId -> !targetId.equals(entity.getId()))
                    .forEach(targetId -> {
                        var relation = new ContextRelation();
                        relation.setFromContextId(entity.getId());
                        relation.setToContextId(targetId);
                        relation.setRelationType("references");
                        contextRelationMapper.insert(relation);
                    });
            log.info("创建上下文关联关系: contextId={}, linkedCount={}", entity.getId(), request.getLinkedContextIds().size());
        }

        // 创建初始条目并向量化写入 RAG 管线
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (StringUtils.hasText(request.getInitialContent())) {
            var item = new ContextItem();
            item.setContextId(entity.getId());
            item.setItemType("document");
            item.setTitle(entity.getName());
            item.setContent(request.getInitialContent());
            item.setSortOrder(0);
            contextItemMapper.insert(item);

            vectorizeContextItem(item.getId(), tenantId, entity.getId(), request.getInitialContent());
        }

        return contextEntityConverter.toVO(entity);
    }

    @Override
    public PageResult<ContextVO> page(ContextQueryRequest query) {
        var page = new Page<ContextEntity>(query.getPage(), query.getSize());
        var wrapper = new LambdaQueryWrapper<ContextEntity>();

        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(w -> w
                    .like(ContextEntity::getName, query.getKeyword())
                    .or()
                    .like(ContextEntity::getDescription, query.getKeyword())
            );
        }
        if (StringUtils.hasText(query.getContextLevel())) {
            wrapper.eq(ContextEntity::getContextLevel, query.getContextLevel());
        }
        if (StringUtils.hasText(query.getProjectId())) {
            wrapper.eq(ContextEntity::getProjectId, query.getProjectId());
        }
        if (StringUtils.hasText(query.getStatus())) {
            wrapper.eq(ContextEntity::getStatus, query.getStatus());
        }
        wrapper.orderByDesc(ContextEntity::getCreatedAt);

        var result = contextEntityMapper.selectPage(page, wrapper);
        var voList = contextEntityConverter.toVOList(result.getRecords());

        voList.forEach(vo -> {
            vo.setItemCount(contextItemHandler.countItems(vo.getId()));
            vo.setTotalTokens(contextItemHandler.sumTokens(vo.getId()));
        });

        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public ContextDetailVO getById(String id) {
        var entity = entityValidator.requireExists(contextEntityMapper, id, ResultCode.CONTEXT_NOT_FOUND);
        var vo = contextEntityConverter.toVO(entity);

        var detailVO = new ContextDetailVO();
        detailVO.setId(vo.getId());
        detailVO.setName(vo.getName());
        detailVO.setContextLevel(vo.getContextLevel());
        detailVO.setProjectId(vo.getProjectId());
        detailVO.setDescription(vo.getDescription());
        detailVO.setStatus(vo.getStatus());
        detailVO.setMetadata(vo.getMetadata());
        detailVO.setCreatedAt(vo.getCreatedAt());
        detailVO.setUpdatedAt(vo.getUpdatedAt());

        var items = contextItemHandler.loadItems(id);
        detailVO.setItems(contextItemConverter.toVOList(items));
        detailVO.setItemCount(items.size());
        detailVO.setTotalTokens(items.stream()
                .mapToInt(item -> item.getTokenCount() != null ? item.getTokenCount() : 0)
                .sum());

        return detailVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ContextVO update(String id, ContextUpdateRequest request) {
        entityValidator.requireExists(contextEntityMapper, id, ResultCode.CONTEXT_NOT_FOUND);

        var updateEntity = new ContextEntity();
        updateEntity.setId(id);
        if (StringUtils.hasText(request.getName())) {
            updateEntity.setName(request.getName());
        }
        if (request.getDescription() != null) {
            updateEntity.setDescription(request.getDescription());
        }
        if (StringUtils.hasText(request.getStatus())) {
            updateEntity.setStatus(request.getStatus());
        }
        if (request.getMetadata() != null) {
            updateEntity.setMetadata(request.getMetadata());
        }
        contextEntityMapper.updateById(updateEntity);

        log.info("更新上下文成功: contextId={}", id);
        return contextEntityConverter.toVO(contextEntityMapper.selectById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        entityValidator.requireExists(contextEntityMapper, id, ResultCode.CONTEXT_NOT_FOUND);
        contextItemHandler.deleteAllItems(id);
        contextSnapshotMapper.delete(
                new LambdaQueryWrapper<ContextSnapshot>()
                        .eq(ContextSnapshot::getContextId, id)
        );
        contextEntityMapper.deleteById(id);
        log.info("删除上下文成功: contextId={}", id);
    }

    // ===== 条目管理 =====

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ContextItemVO addItem(String contextId, ContextItemCreateRequest request) {
        entityValidator.requireExists(contextEntityMapper, contextId, ResultCode.CONTEXT_NOT_FOUND);

        var item = contextItemConverter.fromCreateRequest(request);
        item.setContextId(contextId);
        item.setTokenCount(contextItemHandler.calculateTokenCount(request.getContent()));
        contextItemMapper.insert(item);

        // 向量化写入 RAG 管线
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (StringUtils.hasText(request.getContent())) {
            vectorizeContextItem(item.getId(), tenantId, contextId, request.getContent());
        }

        log.info("添加上下文条目: contextId={}, itemId={}, type={}",
                contextId, item.getId(), item.getItemType());
        return contextItemConverter.toVO(item);
    }

    @Override
    public List<ContextItemVO> listItems(String contextId, String itemType) {
        entityValidator.requireExists(contextEntityMapper, contextId, ResultCode.CONTEXT_NOT_FOUND);
        var items = contextItemHandler.loadItems(contextId, itemType);
        return contextItemConverter.toVOList(items);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ContextItemVO updateItem(String contextId, String itemId, ContextItemUpdateRequest request) {
        entityValidator.requireExists(contextEntityMapper, contextId, ResultCode.CONTEXT_NOT_FOUND);
        var item = contextItemMapper.selectById(itemId);
        if (item == null || !contextId.equals(item.getContextId())) {
            throw new BusinessException(ResultCode.CONTEXT_ITEM_NOT_FOUND);
        }

        var updateItem = new ContextItem();
        updateItem.setId(itemId);
        if (StringUtils.hasText(request.getTitle())) {
            updateItem.setTitle(request.getTitle());
        }
        if (request.getContent() != null) {
            updateItem.setContent(request.getContent());
            updateItem.setTokenCount(contextItemHandler.calculateTokenCount(request.getContent()));
        }
        if (request.getSourceUrl() != null) {
            updateItem.setSourceUrl(request.getSourceUrl());
        }
        if (request.getMetadata() != null) {
            updateItem.setMetadata(request.getMetadata());
        }
        if (request.getSortOrder() != null) {
            updateItem.setSortOrder(request.getSortOrder());
        }
        updateItem.setUpdatedBy(SecurityUtil.getCurrentUserId());
        updateItem.setUpdatedAt(LocalDateTime.now());
        contextItemMapper.updateById(updateItem);

        log.info("更新上下文条目: contextId={}, itemId={}", contextId, itemId);
        return contextItemConverter.toVO(contextItemMapper.selectById(itemId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteItem(String contextId, String itemId) {
        entityValidator.requireExists(contextEntityMapper, contextId, ResultCode.CONTEXT_NOT_FOUND);
        var item = contextItemMapper.selectById(itemId);
        if (item == null || !contextId.equals(item.getContextId())) {
            throw new BusinessException(ResultCode.CONTEXT_ITEM_NOT_FOUND);
        }
        contextItemMapper.deleteById(itemId);
        log.info("删除上下文条目: contextId={}, itemId={}", contextId, itemId);
    }

    // ===== 快照管理 =====

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ContextSnapshotVO createSnapshot(String contextId, String snapshotName) {
        var context = entityValidator.requireExists(contextEntityMapper, contextId, ResultCode.CONTEXT_NOT_FOUND);

        var snapshotData = contextItemHandler.buildSnapshotData(contextId);
        var itemCount = contextItemHandler.countItems(contextId);
        var totalTokens = contextItemHandler.sumTokens(contextId);

        var snapshot = new ContextSnapshot();
        snapshot.setTenantId(context.getTenantId());
        snapshot.setContextId(contextId);
        snapshot.setSnapshotName(snapshotName);
        snapshot.setSnapshotData(snapshotData);
        snapshot.setItemCount(itemCount);
        snapshot.setTotalTokens(totalTokens);
        contextSnapshotMapper.insert(snapshot);

        log.info("创建上下文快照: contextId={}, snapshotId={}, name={}",
                contextId, snapshot.getId(), snapshotName);
        return contextEntityConverter.toSnapshotVO(snapshot);
    }

    @Override
    public List<ContextSnapshotVO> listSnapshots(String contextId) {
        entityValidator.requireExists(contextEntityMapper, contextId, ResultCode.CONTEXT_NOT_FOUND);
        var snapshots = contextSnapshotMapper.selectList(
                new LambdaQueryWrapper<ContextSnapshot>()
                        .eq(ContextSnapshot::getContextId, contextId)
                        .orderByDesc(ContextSnapshot::getCreatedAt)
        );
        return contextEntityConverter.toSnapshotVOList(snapshots);
    }

    @Override
    public ContextSnapshotVO getSnapshotById(String contextId, String snapshotId) {
        entityValidator.requireExists(contextEntityMapper, contextId, ResultCode.CONTEXT_NOT_FOUND);
        var snapshot = contextSnapshotMapper.selectById(snapshotId);
        if (snapshot == null || !contextId.equals(snapshot.getContextId())) {
            throw new BusinessException(ResultCode.CONTEXT_SNAPSHOT_NOT_FOUND);
        }
        return contextEntityConverter.toSnapshotVO(snapshot);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @SuppressWarnings("unchecked")
    public Map<String, Object> restoreSnapshot(String contextId, String snapshotId) {
        entityValidator.requireExists(contextEntityMapper, contextId, ResultCode.CONTEXT_NOT_FOUND);
        var snapshot = contextSnapshotMapper.selectById(snapshotId);
        if (snapshot == null || !contextId.equals(snapshot.getContextId())) {
            throw new BusinessException(ResultCode.CONTEXT_SNAPSHOT_NOT_FOUND);
        }

        var backupSnapshot = createSnapshot(contextId, "恢复前自动备份");
        contextItemHandler.deleteAllItems(contextId);

        var snapshotData = snapshot.getSnapshotData();
        var itemMaps = (List<Map<String, Object>>) snapshotData.get("items");
        int restoredCount = 0;
        if (itemMaps != null) {
            for (var itemMap : itemMaps) {
                var item = new ContextItem();
                item.setContextId(contextId);
                item.setItemType((String) itemMap.get("itemType"));
                item.setTitle((String) itemMap.get("title"));
                item.setContent((String) itemMap.get("content"));
                item.setSourceUrl((String) itemMap.get("sourceUrl"));
                item.setMetadata((Map<String, Object>) itemMap.get("metadata"));
                item.setTokenCount(itemMap.get("tokenCount") instanceof Number n ? n.intValue() : 0);
                item.setSortOrder(itemMap.get("sortOrder") instanceof Number n ? n.intValue() : 0);
                item.setCreatedBy(SecurityUtil.getCurrentUserId());
                contextItemMapper.insert(item);
                restoredCount++;
            }
        }

        log.info("恢复上下文快照: contextId={}, snapshotId={}, restoredItems={}",
                contextId, snapshotId, restoredCount);

        Map<String, Object> result = new HashMap<>();
        result.put("restoredItemCount", restoredCount);
        result.put("backupSnapshotId", backupSnapshot.getId());
        return result;
    }

    // ===== 上下文解析（核心） =====

    /** Token预算分配比例 */
    private static final double GLOBAL_RATIO = 0.20;
    private static final double PROJECT_RATIO = 0.35;
    private static final double TASK_RATIO = 0.30;
    private static final double AGENT_RATIO = 0.15;

    @Override
    public ContextResolvedVO resolve(ContextResolveRequest request) {
        int totalBudget = request.getTokenBudget() != null ? request.getTokenBudget() : 8000;
        var layers = new ArrayList<ContextResolvedVO.ContextLayer>();
        int totalUsed = 0;

        // 第一层：全局上下文（20%预算）
        int globalBudget = (int) (totalBudget * GLOBAL_RATIO);
        var globalLayer = loadContextLayer("global", null, globalBudget);
        layers.add(globalLayer);
        totalUsed += globalLayer.getTokenCount();

        // 第二层：项目上下文（35%预算）
        int projectBudget = (int) (totalBudget * PROJECT_RATIO);
        var projectLayer = loadContextLayer("project", request.getProjectId(), projectBudget);
        layers.add(projectLayer);
        totalUsed += projectLayer.getTokenCount();

        // 第三层：任务上下文（30%预算）
        int taskBudget = (int) (totalBudget * TASK_RATIO);
        var taskLayer = loadContextLayer("task", request.getProjectId(), taskBudget);
        layers.add(taskLayer);
        totalUsed += taskLayer.getTokenCount();

        // 第四层：Agent上下文（15%预算）
        int agentBudget = (int) (totalBudget * AGENT_RATIO);
        var agentLayer = loadContextLayer("agent", request.getProjectId(), agentBudget);
        layers.add(agentLayer);
        totalUsed += agentLayer.getTokenCount();

        // 合并上下文
        var mergedContent = buildMergedContent(layers);

        var result = new ContextResolvedVO();
        result.setMergedContent(mergedContent);
        result.setUsedTokens(totalUsed);
        result.setTokenBudget(totalBudget);
        result.setLayers(layers);

        log.info("上下文解析完成: agentId={}, taskId={}, usedTokens={}/{}",
                request.getAgentId(), request.getTaskId(), totalUsed, totalBudget);
        return result;
    }

    /**
     * 加载指定层级的上下文
     */
    private ContextResolvedVO.ContextLayer loadContextLayer(String level, String projectId, int tokenBudget) {
        var wrapper = new LambdaQueryWrapper<ContextEntity>()
                .eq(ContextEntity::getContextLevel, level)
                .eq(ContextEntity::getStatus, CommonConstant.STATUS_ACTIVE);

        if (projectId != null && !"global".equals(level)) {
            wrapper.eq(ContextEntity::getProjectId, projectId);
        }

        var contexts = contextEntityMapper.selectList(wrapper);
        var contentBuilder = new StringBuilder();
        int totalTokens = 0;
        int itemCount = 0;

        for (var ctx : contexts) {
            var items = contextItemHandler.loadItems(ctx.getId());
            for (var item : items) {
                int itemTokens = item.getTokenCount() != null ? item.getTokenCount() : 0;
                if (totalTokens + itemTokens > tokenBudget) {
                    break; // Token预算耗尽，停止加载
                }
                contentBuilder.append("### ").append(item.getTitle()).append("\n");
                contentBuilder.append(item.getContent()).append("\n\n");
                totalTokens += itemTokens;
                itemCount++;
            }
            if (totalTokens >= tokenBudget) break;
        }

        String levelTitle = switch (level) {
            case "global" -> "全局上下文";
            case "project" -> "项目上下文";
            case "task" -> "任务上下文";
            case "agent" -> "Agent上下文";
            default -> level;
        };

        return new ContextResolvedVO.ContextLayer(
                level, levelTitle, contentBuilder.toString(),
                totalTokens, tokenBudget, itemCount
        );
    }

    /**
     * 合并所有层的上下文内容
     */
    private String buildMergedContent(List<ContextResolvedVO.ContextLayer> layers) {
        var sb = new StringBuilder();
        for (var layer : layers) {
            if (layer.getContent() != null && !layer.getContent().isEmpty()) {
                sb.append("## ").append(layer.getTitle()).append("\n\n");
                sb.append(layer.getContent()).append("\n");
            }
        }
        return sb.toString();
    }

    // ===== 向量化辅助 =====

    /**
     * 向量化上下文条目：优先使用 RAG 管线（DocumentIngestionService），fallback 到原始 MilvusVectorService
     */
    private void vectorizeContextItem(String itemId, String tenantId, String contextId, String content) {
        // 优先使用 LangChain4J RAG 管线
        if (documentIngestionService != null) {
            try {
                int chunks = documentIngestionService.ingestText(itemId, tenantId, contextId, content);
                log.info("RAG 管线向量化完成: itemId={}, chunks={}", itemId, chunks);
                return;
            } catch (Exception e) {
                log.warn("RAG 管线向量化失败，尝试 fallback: itemId={}, error={}", itemId, e.getMessage());
            }
        }

        // Fallback: 原始 MilvusVectorService
        if (milvusVectorService != null) {
            try {
                milvusVectorService.upsertContextItem(itemId, tenantId, null, contextId, content);
                log.info("Milvus 向量化完成: itemId={}", itemId);
            } catch (Exception e) {
                log.warn("Milvus 写入失败（不影响主流程）: itemId={}, error={}", itemId, e.getMessage());
            }
        }
    }

    // ===== 关联关系管理 =====

    @Override
    public java.util.List<ContextRelationVO> listRelations(String contextId) {
        var wrapper = new LambdaQueryWrapper<ContextRelation>()
                .eq(ContextRelation::getFromContextId, contextId)
                .or()
                .eq(ContextRelation::getToContextId, contextId);
        var relations = contextRelationMapper.selectList(wrapper);

        // 收集所有相关上下文ID，批量查询名称
        var contextIds = relations.stream()
                .flatMap(r -> java.util.stream.Stream.of(r.getFromContextId(), r.getToContextId()))
                .distinct()
                .filter(id -> !id.equals(contextId))
                .collect(java.util.stream.Collectors.toList());

        var nameMap = new HashMap<String, String>();
        if (!contextIds.isEmpty()) {
            contextEntityMapper.selectBatchIds(contextIds)
                    .forEach(e -> nameMap.put(e.getId(), e.getName()));
        }
        nameMap.put(contextId, contextEntityMapper.selectById(contextId).getName());

        return relations.stream().map(r -> {
            var vo = new ContextRelationVO();
            vo.setId(r.getId());
            vo.setFromContextId(r.getFromContextId());
            vo.setToContextId(r.getToContextId());
            vo.setFromContextName(nameMap.getOrDefault(r.getFromContextId(), r.getFromContextId()));
            vo.setToContextName(nameMap.getOrDefault(r.getToContextId(), r.getToContextId()));
            vo.setRelationType(r.getRelationType());
            vo.setDescription(r.getDescription());
            vo.setCreatedAt(r.getCreatedAt());
            return vo;
        }).collect(java.util.stream.Collectors.toList());
    }
}