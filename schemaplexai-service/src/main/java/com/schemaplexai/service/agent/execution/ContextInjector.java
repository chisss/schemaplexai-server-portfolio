package com.schemaplexai.service.agent.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.dao.mapper.AgentContextBindingMapper;
import com.schemaplexai.dao.mapper.ContextEntityMapper;
import com.schemaplexai.dao.mapper.ContextItemMapper;
import com.schemaplexai.model.entity.AgentContextBinding;
import com.schemaplexai.model.entity.ContextEntity;
import com.schemaplexai.model.entity.ContextItem;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.context.ContextCacheService;
import com.schemaplexai.service.memory.rag.RagContentRetrieverFactory;
import com.schemaplexai.service.user.UserMemoryInjectionService;
import com.schemaplexai.service.user.UserMemoryPromptPart;
import com.schemaplexai.service.vector.MilvusVectorService;
import com.schemaplexai.service.vector.ScoringService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 四层 + 扩展上下文注入器
 *
 * <p>在原有四层模型基础上，整合 Redis 缓存、Milvus 语义检索、团队共享上下文：
 * <pre>
 * L4            : Agent 专属指令（最高优先级）
 * L1            : 全局知识（global level）
 * L1.5          : Agent 绑定的项目/工作区上下文
 * L2.5          : 语义检索 / 词法 fallback 命中的相关上下文片段
 * L_team        : 团队成员产出摘要（Team Agent 场景）
 * L3            : 运行时任务级上下文（动态注入）
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

    private static final Pattern KEYWORD_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}|[A-Za-z0-9_./:-]{2,}");
    private static final int LEXICAL_FALLBACK_LIMIT = 5;

    private final AgentContextBindingMapper agentContextBindingMapper;
    private final ContextEntityMapper contextEntityMapper;
    private final ContextItemMapper contextItemMapper;
    private final ContextCacheService contextCacheService;
    private final PromptBudgetPlanner promptBudgetPlanner = new PromptBudgetPlanner();
    private final TokenEstimatorSupport tokenEstimatorSupport = new TokenEstimatorSupport();

    /** Milvus 可选注入（Milvus 未启动时为 null，作为 fallback） */
    @Lazy
    @Autowired(required = false)
    private MilvusVectorService milvusVectorService;

    /** LangChain4J RAG ContentRetriever 工厂（Milvus 未启动时为 null） */
    @Lazy
    @Autowired(required = false)
    private RagContentRetrieverFactory ragContentRetrieverFactory;

    /** ONNX Scoring 评分服务（用于 Milvus fallback 路径的 Reranker 精排） */
    @Lazy
    @Autowired(required = false)
    private ScoringService scoringService;

    /** Agent 长期记忆服务（两阶段记忆管线） */
    @Lazy
    @Autowired(required = false)
    private com.schemaplexai.service.agent.memory.AgentMemoryExtractionService agentMemoryService;

    /** 用户长期记忆服务（用户画像与偏好） */
    @Lazy
    @Autowired(required = false)
    private UserMemoryInjectionService userMemoryInjectionService;

    // =========================================================================
    //  公开方法
    // =========================================================================

    /**
     * 向后兼容方法：仅 agentId + extraContext
     */
    public String buildSystemPrompt(String agentId, String extraContext) {
        return buildSystemPromptDetail(agentId, extraContext, null, null, null, null).prompt();
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
        return buildSystemPromptDetail(agentId, extraContext, tenantId, teamAgentId, null, null).prompt();
    }

    /**
     * 完整版 System Prompt 构建（支持运行时附加系统上下文）
     */
    public String buildSystemPrompt(String agentId, String extraContext,
                                    String tenantId, String teamAgentId,
                                    List<String> additionalSystemContexts) {
        return buildSystemPromptDetail(agentId, extraContext, tenantId, teamAgentId, additionalSystemContexts, null).prompt();
    }

    /**
     * 返回带观测指标的 System Prompt 构建结果。
     */
    public PromptBuildResult buildSystemPromptDetail(String agentId, String extraContext,
                                                     String tenantId, String teamAgentId,
                                                     List<String> additionalSystemContexts,
                                                     AiModelConfig modelConfig) {
        return buildSystemPromptDetail(agentId, extraContext, tenantId, teamAgentId,
                additionalSystemContexts, modelConfig, null, null, false);
    }

    /**
     * 返回带用户记忆观测指标的 System Prompt 构建结果。
     */
    public PromptBuildResult buildSystemPromptDetail(String agentId, String extraContext,
                                                     String tenantId, String teamAgentId,
                                                     List<String> additionalSystemContexts,
                                                     AiModelConfig modelConfig,
                                                     String userId,
                                                     String projectId,
                                                     boolean temporaryChat) {
        boolean hasSemanticContext = StringUtils.hasText(tenantId) && StringUtils.hasText(extraContext);
        boolean hasTeamContext = StringUtils.hasText(teamAgentId);
        boolean hasRuntimeContext = additionalSystemContexts != null && !additionalSystemContexts.isEmpty();
        PromptBudgetPlanner.PromptBudget budget = promptBudgetPlanner.plan(
                modelConfig,
                hasSemanticContext,
                hasTeamContext,
                hasRuntimeContext || StringUtils.hasText(extraContext)
        );

        String promptVariant = buildPromptVariant(modelConfig, budget);
        String cachedStaticPart = modelConfig == null
                ? contextCacheService.getAgentPrompt(agentId)
                : contextCacheService.getAgentPrompt(agentId, promptVariant);
        String staticPart;
        if (cachedStaticPart != null) {
            staticPart = cachedStaticPart;
            log.debug("命中 Agent prompt 缓存: agentId={}", agentId);
        } else {
            staticPart = buildStaticPart(agentId, budget);
            if (modelConfig == null) {
                contextCacheService.cacheAgentPrompt(agentId, staticPart);
            } else {
                contextCacheService.cacheAgentPrompt(agentId, promptVariant, staticPart);
            }
        }

        DynamicPromptPart dynamicPart = buildDynamicPart(agentId, tenantId, teamAgentId, extraContext,
                userId, projectId, temporaryChat, budget);
        String runtimePart = buildRuntimeSystemPart(additionalSystemContexts, budget.l3BudgetChars());

        StringBuilder promptBuilder = new StringBuilder(staticPart);
        if (StringUtils.hasText(runtimePart)) {
            promptBuilder.append("\n\n---\n\n").append(runtimePart);
        }
        if (StringUtils.hasText(dynamicPart.prompt())) {
            promptBuilder.append("\n\n---\n\n").append(dynamicPart.prompt());
        }

        String prompt = promptBuilder.toString();
        return new PromptBuildResult(
                prompt,
                staticPart.length(),
                dynamicPart.prompt().length(),
                runtimePart.length(),
                prompt.length(),
                tokenEstimatorSupport.estimateText(modelConfig, prompt),
                dynamicPart.retrievalSource(),
                dynamicPart.retrievalHitCount(),
                dynamicPart.retrievalRawChars(),
                dynamicPart.retrievalCompressedChars(),
                budget.totalPromptBudgetChars(),
                dynamicPart.userMemoryStaticCount(),
                dynamicPart.userMemoryContextualCount()
        );
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
    private String buildStaticPart(String agentId, PromptBudgetPlanner.PromptBudget budget) {
        List<String> sections = new ArrayList<>();

        // L4: Agent 专属指令（最优先，放在最前）
        String agentInstructions = loadAgentInstructions(agentId);
        if (StringUtils.hasText(agentInstructions)) {
            sections.add(compressText(agentInstructions, budget.l4BudgetChars()));
        }

        // L1: 全局上下文
        List<String> globalContent = loadContextByLevel("global", null);
        if (!globalContent.isEmpty()) {
            String globalText = String.join("\n\n", globalContent);
            sections.add("## 全局知识\n" + compressText(globalText, budget.l1BudgetChars()));
        }

        String boundContext = loadBoundContextContent(agentId);
        if (StringUtils.hasText(boundContext)) {
            sections.add("## 绑定上下文\n" + compressText(boundContext, budget.boundBudgetChars()));
        }

        return String.join("\n\n---\n\n", sections);
    }

    /**
     * 构建动态部分（L2.5 Milvus + L_team + L3），每次实时生成
     */
    private DynamicPromptPart buildDynamicPart(String agentId, String tenantId,
                                               String teamAgentId, String extraContext,
                                               String userId, String projectId,
                                               boolean temporaryChat,
                                               PromptBudgetPlanner.PromptBudget budget) {
        List<String> sections = new ArrayList<>();
        String retrievalSource = "empty";
        int retrievalHitCount = 0;
        int retrievalRawChars = 0;
        int retrievalCompressedChars = 0;
        int userMemoryStaticCount = 0;
        int userMemoryContextualCount = 0;

        // L2.5: 语义检索（优先使用 LangChain4J ContentRetriever，fallback 到原始 Milvus）
        if (StringUtils.hasText(tenantId) && StringUtils.hasText(extraContext)) {
            RetrievalResult retrievalResult = retrieveSemanticContext(tenantId, agentId, extraContext);
            if (!retrievalResult.contents().isEmpty()) {
                retrievalSource = retrievalResult.source();
                retrievalHitCount = retrievalResult.contents().size();
                String semanticText = String.join("\n\n", retrievalResult.contents());
                retrievalRawChars = semanticText.length();
                String compressed = compressText(semanticText, budget.l25BudgetChars());
                retrievalCompressedChars = compressed.length();
                sections.add("## 相关背景知识（" + retrievalSource + "）\n" + compressed);
                log.debug("上下文检索命中 {} 条: agentId={}, source={}", retrievalResult.contents().size(), agentId, retrievalSource);
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
                sections.add("## 团队成员已完成输出\n" + compressText(teamText, budget.teamBudgetChars()));
                log.debug("注入团队共享上下文: teamAgentId={}, members={}", teamAgentId, teamContext.size());
            }
        }

        // L_user: 用户画像与偏好（按用户隔离，不能进入 Agent 静态缓存）
        if (userMemoryInjectionService != null && StringUtils.hasText(tenantId) && StringUtils.hasText(userId)) {
            try {
                UserMemoryPromptPart userMemoryPart = userMemoryInjectionService.buildPromptPart(
                        tenantId, userId, agentId, projectId, extraContext, temporaryChat);
                if (StringUtils.hasText(userMemoryPart.staticPrompt())) {
                    sections.add(compressText(userMemoryPart.staticPrompt(), 1200));
                    userMemoryStaticCount = userMemoryPart.staticCount();
                }
                if (StringUtils.hasText(userMemoryPart.contextualPrompt())) {
                    sections.add(compressText(userMemoryPart.contextualPrompt(), 1200));
                    userMemoryContextualCount = userMemoryPart.contextualCount();
                }
            } catch (Exception e) {
                log.debug("用户记忆注入跳过: userId={}, agentId={}, error={}", userId, agentId, e.getMessage());
            }
        }

        // L_memory: Agent 长期记忆（跨会话知识积累）
        if (agentMemoryService != null && StringUtils.hasText(tenantId)) {
            try {
                var memories = agentMemoryService.getActiveMemories(tenantId, agentId, 10);
                if (memories != null && !memories.isEmpty()) {
                    StringBuilder memorySb = new StringBuilder();
                    for (var memory : memories) {
                        memorySb.append("- [").append(memory.getMemoryType()).append("] ")
                                .append(memory.getContent()).append("\n");
                    }
                    sections.add("## 历史记忆\n" + compressText(memorySb.toString().trim(), 2000));
                    log.debug("注入Agent长期记忆: agentId={}, count={}", agentId, memories.size());
                }
            } catch (Exception e) {
                log.debug("记忆注入跳过: agentId={}, error={}", agentId, e.getMessage());
            }
        }

        // L3: 运行时附加上下文（任务级）
        if (StringUtils.hasText(extraContext)) {
            sections.add("## 当前任务上下文\n" + compressText(extraContext, budget.l3BudgetChars()));
        }

        return new DynamicPromptPart(String.join("\n\n---\n\n", sections),
                retrievalSource, retrievalHitCount, retrievalRawChars, retrievalCompressedChars,
                userMemoryStaticCount, userMemoryContextualCount);
    }

    private String buildRuntimeSystemPart(List<String> additionalSystemContexts, int runtimeBudget) {
        if (additionalSystemContexts == null || additionalSystemContexts.isEmpty()) {
            return "";
        }
        List<String> normalizedContexts = additionalSystemContexts.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (normalizedContexts.isEmpty()) {
            return "";
        }
        return "## 运行时角色补充上下文\n" + compressText(String.join("\n\n", normalizedContexts), runtimeBudget);
    }

    /**
     * 加载 Agent 绑定上下文中的专属指令条目
     */
    private String loadAgentInstructions(String agentId) {
        List<AgentContextBinding> bindings = agentContextBindingMapper.selectList(
                new LambdaQueryWrapper<AgentContextBinding>()
                        .eq(AgentContextBinding::getAgentId, agentId)
                        .eq(AgentContextBinding::getStatus, CommonConstant.STATUS_ACTIVE)
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
     * 加载 Agent 显式绑定的上下文内容。
     *
     * <p>这类上下文通常承载真实项目结构、工作区约束和角色补充说明，不能只依赖向量检索命中。
     */
    private String loadBoundContextContent(String agentId) {
        List<AgentContextBinding> bindings = agentContextBindingMapper.selectList(
                new LambdaQueryWrapper<AgentContextBinding>()
                        .eq(AgentContextBinding::getAgentId, agentId)
                        .orderByAsc(AgentContextBinding::getSortOrder)
        );
        if (bindings.isEmpty()) {
            return "";
        }

        List<String> sections = new ArrayList<>();
        for (AgentContextBinding binding : bindings) {
            if (binding == null) {
                continue;
            }
            if (StringUtils.hasText(binding.getContent())) {
                sections.add(formatBoundContextSection(binding.getTitle(), binding.getContent()));
            }
            if (!StringUtils.hasText(binding.getContextId())) {
                continue;
            }
            ContextEntity context = contextEntityMapper.selectById(binding.getContextId());
            if (context == null
                    || !CommonConstant.STATUS_ACTIVE.equals(context.getStatus())
                    || "global".equalsIgnoreCase(context.getContextLevel())) {
                continue;
            }
            List<String> contents = contextItemMapper.selectList(
                            new LambdaQueryWrapper<ContextItem>()
                                    .eq(ContextItem::getContextId, context.getId())
                                    .orderByAsc(ContextItem::getSortOrder))
                    .stream()
                    .filter(item -> item != null && !Boolean.TRUE.equals(item.getIsAgentInstructions()))
                    .map(ContextItem::getContent)
                    .filter(StringUtils::hasText)
                    .toList();
            if (contents.isEmpty()) {
                continue;
            }
            sections.add(formatBoundContextSection(
                    StringUtils.hasText(binding.getTitle()) ? binding.getTitle() : context.getName(),
                    String.join("\n\n", contents)
            ));
        }
        return String.join("\n\n", sections);
    }

    private String formatBoundContextSection(String title, String content) {
        String resolvedContent = StringUtils.hasText(content) ? content.trim() : "";
        if (!StringUtils.hasText(resolvedContent)) {
            return "";
        }
        if (!StringUtils.hasText(title)) {
            return resolvedContent;
        }
        return "### " + title.trim() + "\n" + resolvedContent;
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
     * 语义检索：优先使用 LangChain4J ContentRetriever，fallback 到原始 MilvusVectorService，再降级到 DB 词法检索
     */
    private RetrievalResult retrieveSemanticContext(String tenantId, String agentId, String query) {
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
                        return new RetrievalResult("语义检索", results);
                    }
                }
            } catch (Exception e) {
                log.debug("LangChain4J RAG 检索异常，尝试 fallback: {}", e.getMessage());
            }
        }

        // Fallback: 原始 MilvusVectorService
        if (milvusVectorService != null) {
            try {
                List<String> results = boundContextIds.isEmpty()
                        ? milvusVectorService.searchSimilarContext(tenantId, agentId, query, 5)
                        : milvusVectorService.searchSimilarContext(tenantId, boundContextIds, query, 5);
                if (results != null && !results.isEmpty()) {
                    // 若 Reranker 可用，对 Milvus fallback 结果也进行精排
                    if (scoringService != null && scoringService.isAvailable()) {
                        List<ScoringService.ScoredPassage> reranked = scoringService.rerank(query, results, 5);
                        results = reranked.stream().map(ScoringService.ScoredPassage::text).toList();
                    }
                    return new RetrievalResult("语义检索", results);
                }
            } catch (Exception e) {
                log.debug("Milvus 语义检索跳过: {}", e.getMessage());
            }
        }

        List<String> lexicalHits = retrieveLexicalContext(boundContextIds, query);
        if (!lexicalHits.isEmpty()) {
            return new RetrievalResult("词法关键词检索", lexicalHits);
        }

        return new RetrievalResult("empty", List.of());
    }

    private List<String> loadBoundContextIds(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return List.of();
        }
        return agentContextBindingMapper.selectList(
                        new LambdaQueryWrapper<AgentContextBinding>()
                                .eq(AgentContextBinding::getAgentId, agentId)
                                .eq(AgentContextBinding::getStatus, CommonConstant.STATUS_ACTIVE)
                                .isNotNull(AgentContextBinding::getContextId)
                                .orderByAsc(AgentContextBinding::getSortOrder))
                .stream()
                .map(AgentContextBinding::getContextId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private List<String> retrieveLexicalContext(List<String> contextIds, String query) {
        if (!StringUtils.hasText(query) || contextIds == null || contextIds.isEmpty()) {
            return List.of();
        }
        List<String> keywords = extractKeywords(query);
        if (keywords.isEmpty()) {
            return List.of();
        }
        List<ContextItem> items = contextItemMapper.selectList(new LambdaQueryWrapper<ContextItem>()
                .in(ContextItem::getContextId, contextIds)
                .orderByAsc(ContextItem::getSortOrder));
        if (items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .filter(item -> item != null && !Boolean.TRUE.equals(item.getIsAgentInstructions()))
                .map(item -> Map.entry(item, lexicalScore(item, keywords)))
                .filter(entry -> entry.getValue() > 0)
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(LEXICAL_FALLBACK_LIMIT)
                .map(entry -> formatLexicalHit(entry.getKey()))
                .filter(StringUtils::hasText)
                .toList();
    }

    private int lexicalScore(ContextItem item, List<String> keywords) {
        if (item == null) {
            return 0;
        }
        String haystack = ((item.getTitle() != null ? item.getTitle() : "") + "\n" + (item.getContent() != null ? item.getContent() : "")).toLowerCase();
        if (!StringUtils.hasText(haystack)) {
            return 0;
        }
        int score = 0;
        for (String keyword : keywords) {
            String needle = keyword.toLowerCase();
            if (!StringUtils.hasText(needle)) {
                continue;
            }
            int from = 0;
            while (from >= 0) {
                int found = haystack.indexOf(needle, from);
                if (found < 0) {
                    break;
                }
                score += item.getTitle() != null && item.getTitle().toLowerCase().contains(needle) ? 5 : 2;
                from = found + needle.length();
            }
        }
        return score;
    }

    private String formatLexicalHit(ContextItem item) {
        if (item == null || !StringUtils.hasText(item.getContent())) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(item.getTitle())) {
            builder.append("### ").append(item.getTitle().trim()).append("\n");
        }
        builder.append(item.getContent().trim());
        return builder.toString();
    }

    private List<String> extractKeywords(String query) {
        Matcher matcher = KEYWORD_PATTERN.matcher(query);
        List<String> keywords = new ArrayList<>();
        while (matcher.find()) {
            String candidate = matcher.group();
            if (StringUtils.hasText(candidate) && keywords.stream().noneMatch(candidate::equalsIgnoreCase)) {
                keywords.add(candidate.trim());
            }
            if (keywords.size() >= 12) {
                break;
            }
        }
        return keywords;
    }

    /**
     * 按预算压缩文本，优先保留标题、列表与前序关键信息，而不是直接无脑截断。
     */
    private String compressText(String text, int budget) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
        if (normalized.length() <= budget) {
            return normalized;
        }
        List<String> segments = List.of(normalized.split("\\n\\n"));
        StringBuilder builder = new StringBuilder();
        for (String segment : segments) {
            String compacted = segment.trim();
            if (!StringUtils.hasText(compacted)) {
                continue;
            }
            String candidate = compacted.length() > Math.max(200, budget / 2)
                    ? compacted.substring(0, Math.max(200, budget / 2)) + "..."
                    : compacted;
            if (builder.length() + candidate.length() + 2 > budget) {
                break;
            }
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(candidate);
        }
        if (builder.isEmpty()) {
            builder.append(normalized, 0, Math.min(normalized.length(), budget));
        }
        builder.append("\n...[上下文已压缩，原始 ").append(normalized.length()).append(" 字符]");
        return builder.toString();
    }

    private String buildPromptVariant(AiModelConfig modelConfig, PromptBudgetPlanner.PromptBudget budget) {
        if (modelConfig == null) {
            return "default";
        }
        return modelConfig.getModelId() + "-" + budget.totalPromptBudgetChars();
    }

    public record PromptBuildResult(String prompt,
                                    int staticChars,
                                    int dynamicChars,
                                    int runtimeChars,
                                    int totalChars,
                                    int estimatedTokens,
                                    String retrievalSource,
                                    int retrievalHitCount,
                                    int retrievalRawChars,
                                    int retrievalCompressedChars,
                                    int promptBudgetChars,
                                    int userMemoryStaticCount,
                                    int userMemoryContextualCount) {
    }

    private record DynamicPromptPart(String prompt,
                                     String retrievalSource,
                                     int retrievalHitCount,
                                     int retrievalRawChars,
                                     int retrievalCompressedChars,
                                     int userMemoryStaticCount,
                                     int userMemoryContextualCount) {
    }

    private record RetrievalResult(String source, List<String> contents) {
    }
}
