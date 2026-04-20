package com.schemaplexai.service.agent.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.schemaplexai.common.enums.MemoryPhaseEnum;
import com.schemaplexai.dao.mapper.AgentMemoryMapper;
import com.schemaplexai.model.entity.AgentMemory;
import com.schemaplexai.service.ai.AIModelRouter;
import com.schemaplexai.service.ai.AiModelConfig;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Agent记忆合并服务（Phase 2）
 * <p>参考 Codex CLI 的记忆合并机制：
 * 定时将多次提取的原始记忆合并为精炼的长期记忆，
 * 去重、更新、淘汰过时信息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMemoryConsolidationService {

    private static final int MIN_RAW_MEMORIES_FOR_CONSOLIDATION = 3;
    private static final int MAX_CONSOLIDATED_MEMORIES = 20;
    private static final String CONSOLIDATION_PROMPT = """
            你是一个记忆合并助手。将以下原始记忆条目合并为精炼的长期记忆。

            合并规则：
            1. 去除重复或高度相似的条目
            2. 合并互补的信息为更完整的陈述
            3. 保留最新、最准确的信息，淘汰过时的
            4. 每条记忆必须独立、自包含
            5. 保持原有的类型分类
            6. 最多输出 %d 条合并后的记忆

            输出格式（每条一行）：
            [类型] 记忆内容

            原始记忆：
            %s
            """;
    private static final Pattern MEMORY_LINE_PATTERN = Pattern.compile(
            "^\\[(FACT|PREFERENCE|CONSTRAINT|PATTERN)]\\s+(.+)$", Pattern.MULTILINE);

    private final AgentMemoryMapper memoryMapper;
    private final AIModelRouter modelRouter;

    /**
     * 合并指定Agent的原始记忆
     */
    public int consolidate(String tenantId, String agentId) {
        List<AgentMemory> rawMemories = memoryMapper.selectList(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getTenantId, tenantId)
                .eq(AgentMemory::getAgentId, agentId)
                .eq(AgentMemory::getPhase, MemoryPhaseEnum.RAW.getCode())
                .isNull(AgentMemory::getConsolidatedInto)
                .orderByAsc(AgentMemory::getCreatedAt));

        if (rawMemories.size() < MIN_RAW_MEMORIES_FOR_CONSOLIDATION) {
            return 0;
        }

        AiModelConfig model = modelRouter.resolvePrimaryModel(tenantId);
        if (model == null) {
            log.warn("未配置主模型，跳过记忆合并: tenantId={}", tenantId);
            return 0;
        }

        String rawText = rawMemories.stream()
                .map(m -> "[" + m.getMemoryType() + "] " + m.getContent())
                .collect(Collectors.joining("\n"));

        try {
            ChatModel chatModel = modelRouter.buildChatModel(model);
            String prompt = String.format(CONSOLIDATION_PROMPT, MAX_CONSOLIDATED_MEMORIES, rawText);
            ChatResponse response = chatModel.chat(List.of(
                    SystemMessage.from("你是一个精确的记忆管理助手。"),
                    UserMessage.from(prompt)
            ));
            String output = response.aiMessage().text();
            List<AgentMemory> consolidated = parseConsolidated(tenantId, agentId, output);

            // 标记原始记忆为已合并
            List<String> rawIds = rawMemories.stream().map(AgentMemory::getId).toList();
            for (AgentMemory cm : consolidated) {
                memoryMapper.insert(cm);
                // 将原始记忆指向合并结果
                for (String rawId : rawIds) {
                    memoryMapper.update(null, new LambdaUpdateWrapper<AgentMemory>()
                            .eq(AgentMemory::getId, rawId)
                            .set(AgentMemory::getConsolidatedInto, cm.getId()));
                }
            }

            log.info("记忆合并完成: agentId={}, raw={}, consolidated={}", agentId, rawMemories.size(), consolidated.size());
            return consolidated.size();
        } catch (Exception e) {
            log.error("记忆合并失败: agentId={}, error={}", agentId, e.getMessage());
            return 0;
        }
    }
    /**
     * 批量合并所有租户的待合并记忆
     */
    public void consolidateAll() {
        // 查找有待合并记忆的 agent
        List<AgentMemory> samples = memoryMapper.selectList(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getPhase, MemoryPhaseEnum.RAW.getCode())
                .isNull(AgentMemory::getConsolidatedInto)
                .select(AgentMemory::getTenantId, AgentMemory::getAgentId)
                .groupBy(AgentMemory::getTenantId, AgentMemory::getAgentId));

        Map<String, List<AgentMemory>> grouped = samples.stream()
                .collect(Collectors.groupingBy(m -> m.getTenantId() + "|" + m.getAgentId()));

        int totalConsolidated = 0;
        for (Map.Entry<String, List<AgentMemory>> entry : grouped.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            try {
                totalConsolidated += consolidate(parts[0], parts[1]);
            } catch (Exception e) {
                log.warn("Agent记忆合并异常: key={}, error={}", entry.getKey(), e.getMessage());
            }
        }
        if (totalConsolidated > 0) {
            log.info("批量记忆合并完成: totalConsolidated={}", totalConsolidated);
        }
    }

    private List<AgentMemory> parseConsolidated(String tenantId, String agentId, String output) {
        List<AgentMemory> memories = new ArrayList<>();
        if (!StringUtils.hasText(output)) return memories;

        Matcher matcher = MEMORY_LINE_PATTERN.matcher(output);
        while (matcher.find() && memories.size() < MAX_CONSOLIDATED_MEMORIES) {
            String type = matcher.group(1);
            String content = matcher.group(2).trim();
            if (content.length() > 500) {
                content = content.substring(0, 500);
            }
            AgentMemory memory = new AgentMemory();
            memory.setTenantId(tenantId);
            memory.setAgentId(agentId);
            memory.setMemoryType(type);
            memory.setPhase(MemoryPhaseEnum.CONSOLIDATED.getCode());
            memory.setContent(content);
            memory.setRelevanceScore(new BigDecimal("0.90"));
            memory.setExpiresAt(LocalDateTime.now().plusDays(30));
            memories.add(memory);
        }
        return memories;
    }
}
