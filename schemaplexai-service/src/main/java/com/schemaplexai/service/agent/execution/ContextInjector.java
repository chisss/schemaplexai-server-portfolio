package com.schemaplexai.service.agent.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.dao.mapper.AgentContextBindingMapper;
import com.schemaplexai.dao.mapper.ContextEntityMapper;
import com.schemaplexai.dao.mapper.ContextItemMapper;
import com.schemaplexai.model.entity.AgentContextBinding;
import com.schemaplexai.model.entity.ContextEntity;
import com.schemaplexai.model.entity.ContextItem;
import com.schemaplexai.service.context.ContextCacheService;
import com.schemaplexai.service.memory.rag.RagContentRetrieverFactory;
import com.schemaplexai.service.vector.MilvusVectorService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 四层 + 扩展上下文注入器
 *
 * <p>在原有四层模型基础上，整合 Redis 缓存、Milvus 语义检索、团队共享上下文：
 * <pre>
 * L4 (8000 chars)  : Agent 专属指令（最高优先级）
 * L1 (4800 chars)  : 全局知识（global level）
 * L2.5 (3200 chars): Milvus 语义检索命中的相关上下文片段
 * L_team(2000 chars): 团队成员产出摘要（Team Agent 场景）
 * L3 (2400 chars)  : 运行时任务级上下文（动态注入）
 * </pre>
 *
 * <p>优化策略：
 * <ul>
 *   <li>L4+L1 静态部分写入 Redis，TTL 5 分钟，避免重复 DB 查询</li>
 *   <li>L2.5 Milvus 检索为可选增强，不可用时静默跳过</li>
 *   <li>每层有独立 token 预算（字符数上限），防止上下文爆炸</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextInjector {

    /** L4 Agent 专属指令字符上限 */
    private static final int BUDGET_L4 = 8000;
    /** L1 全局知识字符上限 */
    private static final int BUDGET_L1 = 4800;
    /** L2.5 Milvus 语义检索字符上限 */
    private static final int BUDGET_L2_5 = 3200;
    /** L_team 团队共享上下文字符上限 */
    private static final int BUDGET_TEAM = 2000;
    /** L3 任务级上下文字符上限 */
    private static final int BUDGET_L3 = 2400;

    private final AgentContextBindingMapper agentContextBindingMapper;
    private final ContextEntityMapper contextEntityMapper;
    private final ContextItemMapper contextItemMapper;
    private final ContextCacheService contextCacheService;

    /** Milvus 可选注入（Milvus 未启动时为 null，作为 fallback） */
    @Lazy
    @Autowired(required = false)
    private MilvusVectorService milvusVectorService;

    /** LangChain4J RAG ContentRetriever 工厂（Milvus 未启动时为 null） */
    @Lazy
    @Autowired(required = false)
    private RagContentRetrieverFactory ragContentRetrieverFactory;

    // =========================================================================
    //  公开方法
    // =========================================================================

    /**
     * 向后兼容方法：仅 agentId + extraContext
     */
    public String buildSystemPrompt(String agentId, String extraContext) {
        return buildSystemPrompt(agentId, extraContext, null, null);
    }

    /**
     * 完整版 System Prompt 构建（整合 Redis/Milvus/团队共享上下文）
     *
     * @param agentId     Agent ID
     * @param extraContext 运行时附加上下文（L3 层级）
     * @param tenantId    租户 ID（Milvus 检索隔离，可空则跳过语义检索）
     * @param teamAgentId Team Agent ID（非团队场景传 null）
     * @return 组装好的 System Prompt 字符串
     */
    public String buildSystemPrompt(String agentId, String extraContext,
                                    String tenantId, String teamAgentId) {
        // 尝试从 Redis 读取静态部分（L4+L1）
        String cachedStaticPart = contextCacheService.getAgentPrompt(agentId);
        String staticPart;
        if (cachedStaticPart != null) {
            staticPart = cachedStaticPart;
            log.debug("命中 Agent prompt 缓存: agentId={}", agentId);
        } else {
            staticPart = buildStaticPart(agentId);
            contextCacheService.cacheAgentPrompt(agentId, staticPart);
        }

        // 动态部分（L2.5 + L_team + L3）
        String dynamicPart = buildDynamicPart(agentId, tenantId, teamAgentId, extraContext);

        return dynamicPart.isBlank() ? staticPart : staticPart + "\n\n---\n\n" + dynamicPart;
    }

    /**
     * 失效 Agent 系统提示词缓存（上下文条目变更时调用）
     *
     * @param agentId Agent ID
     */
    public void evictAgentCache(String agentId) {
        contextCacheService.evictAgentPrompt(agentId);
        log.debug("已失效 Agent prompt 缓存: agentId={}", agentId);
    }

    // =========================================================================
    //  私有方法
    // =========================================================================

    /**
     * 构建静态部分（L4 + L1），会被 Redis 缓存
     */
    private String buildStaticPart(String agentId) {
        List<String> sections = new ArrayList<>();

        // L4: Agent 专属指令（最优先，放在最前）
        String agentInstructions = loadAgentInstructions(agentId);
        if (StringUtils.hasText(agentInstructions)) {
            sections.add(truncate(agentInstructions, BUDGET_L4));
        }

        // L1: 全局上下文
        List<String> globalContent = loadContextByLevel("global", null);
        if (!globalContent.isEmpty()) {
            String globalText = String.join("\n\n", globalContent);
            sections.add("## 全局知识\n" + truncate(globalText, BUDGET_L1));
        }

        return String.join("\n\n---\n\n", sections);
    }

    /**
     * 构建动态部分（L2.5 Milvus + L_team + L3），每次实时生成
     */
    private String buildDynamicPart(String agentId, String tenantId,
                                    String teamAgentId, String extraContext) {
        List<String> sections = new ArrayList<>();

        // L2.5: 语义检索（优先使用 LangChain4J ContentRetriever，fallback 到原始 Milvus）
        if (StringUtils.hasText(tenantId) && StringUtils.hasText(extraContext)) {
            List<String> semanticHits = retrieveSemanticContext(tenantId, agentId, extraContext);
            if (!semanticHits.isEmpty()) {
                String semanticText = String.join("\n\n", semanticHits);
                sections.add("## 相关背景知识（语义检索）\n" + truncate(semanticText, BUDGET_L2_5));
                log.debug("语义检索命中 {} 条: agentId={}", semanticHits.size(), agentId);
            }
        }

        // L_team: 团队共享上下文（Team Agent 场景）
        if (StringUtils.hasText(teamAgentId)) {
            Map<String, String> teamContext = contextCacheService.getTeamSharedContext(teamAgentId);
            if (!teamContext.isEmpty()) {
                StringBuilder teamSb = new StringBuilder();
                teamContext.forEach((subId, summary) ->
                        teamSb.append("- ").append(summary).append("\n"));
                String teamText = teamSb.toString().trim();
                sections.add("## 团队成员已完成输出\n" + truncate(teamText, BUDGET_TEAM));
                log.debug("注入团队共享上下文: teamAgentId={}, members={}", teamAgentId, teamContext.size());
            }
        }

        // L3: 运行时附加上下文（任务级）
        if (StringUtils.hasText(extraContext)) {
            sections.add("## 当前任务上下文\n" + truncate(extraContext, BUDGET_L3));
        }

        return String.join("\n\n---\n\n", sections);
    }

    /**
     * 加载 Agent 绑定上下文中的专属指令条目
     */
    private String loadAgentInstructions(String agentId) {
        List<AgentContextBinding> bindings = agentContextBindingMapper.selectList(
                new LambdaQueryWrapper<AgentContextBinding>()
                        .eq(AgentContextBinding::getAgentId, agentId)
                        .isNotNull(AgentContextBinding::getContextId));
        for (AgentContextBinding binding : bindings) {
            ContextItem item = contextItemMapper.selectOne(
                    new LambdaQueryWrapper<ContextItem>()
                            .eq(ContextItem::getContextId, binding.getContextId())
                            .eq(ContextItem::getIsAgentInstructions, true)
                            .last("LIMIT 1"));
            if (item != null) {
                return item.getContent();
            }
        }
        return null;
    }

    /**
     * 按层级加载上下文条目内容
     */
    private List<String> loadContextByLevel(String level, String projectId) {
        LambdaQueryWrapper<ContextEntity> ctxQuery = new LambdaQueryWrapper<ContextEntity>()
                .eq(ContextEntity::getContextLevel, level)
                .eq(ContextEntity::getStatus, CommonConstant.STATUS_ACTIVE);
        if (projectId != null) {
            ctxQuery.eq(ContextEntity::getProjectId, projectId);
        }
        List<ContextEntity> contexts = contextEntityMapper.selectList(ctxQuery);

        List<ContextItem> allItems = new ArrayList<>();
        for (ContextEntity ctx : contexts) {
            allItems.addAll(contextItemMapper.selectList(
                    new LambdaQueryWrapper<ContextItem>()
                            .eq(ContextItem::getContextId, ctx.getId())
                            .orderByAsc(ContextItem::getSortOrder)));
        }
        return allItems.stream()
                .sorted(Comparator.comparing(item -> item.getSortOrder() != null ? item.getSortOrder() : 0))
                .map(ContextItem::getContent)
                .filter(c -> c != null && !c.isBlank())
                .toList();
    }

    /**
     * 语义检索：优先使用 LangChain4J ContentRetriever，fallback 到原始 MilvusVectorService
     */
    private List<String> retrieveSemanticContext(String tenantId, String agentId, String query) {
        List<String> boundContextIds = loadBoundContextIds(agentId);
        // 优先使用 LangChain4J RAG ContentRetriever
        if (ragContentRetrieverFactory != null) {
            try {
                ContentRetriever retriever = ragContentRetrieverFactory.createRetriever(tenantId, boundContextIds);
                if (retriever != null) {
                    List<Content> contents = retriever.retrieve(Query.from(query));
                    List<String> results = contents.stream()
                            .map(c -> c.textSegment().text())
                            .filter(t -> t != null && !t.isBlank())
                            .toList();
                    if (!results.isEmpty()) {
                        return results;
                    }
                }
            } catch (Exception e) {
                log.debug("LangChain4J RAG 检索异常，尝试 fallback: {}", e.getMessage());
            }
        }

        // Fallback: 原始 MilvusVectorService
        if (milvusVectorService != null) {
            try {
                return milvusVectorService.searchSimilarContext(tenantId, agentId, query, 5);
            } catch (Exception e) {
                log.debug("Milvus 语义检索跳过: {}", e.getMessage());
            }
        }

        return List.of();
    }

    private List<String> loadBoundContextIds(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return List.of();
        }
        return agentContextBindingMapper.selectList(
                        new LambdaQueryWrapper<AgentContextBinding>()
                                .eq(AgentContextBinding::getAgentId, agentId)
                                .isNotNull(AgentContextBinding::getContextId)
                                .orderByAsc(AgentContextBinding::getSortOrder))
                .stream()
                .map(AgentContextBinding::getContextId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    /**
     * 按字符预算截断文本，超出时追加省略提示
     */
    private String truncate(String text, int budget) {
        if (text == null) return "";
        if (text.length() <= budget) return text;
        return text.substring(0, budget) + "\n...[上下文已截断，超出预算 " + budget + " 字符]";
    }
}
