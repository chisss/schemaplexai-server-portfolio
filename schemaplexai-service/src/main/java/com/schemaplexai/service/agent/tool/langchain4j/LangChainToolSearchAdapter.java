package com.schemaplexai.service.agent.tool.langchain4j;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.tool.ToolServiceContext;
import dev.langchain4j.service.tool.search.ToolSearchService;
import dev.langchain4j.service.tool.search.simple.SimpleToolSearchStrategy;

import java.util.List;
import java.util.stream.Collectors;

import static dev.langchain4j.agent.tool.SearchBehavior.ALWAYS_VISIBLE;
import static dev.langchain4j.agent.tool.ToolSpecification.METADATA_SEARCH_BEHAVIOR;

/**
 * LangChain4j 工具搜索适配器，统一隔离框架 adjust API 的参数形态变化。
 */
public class LangChainToolSearchAdapter {

    private final ToolSearchService toolSearchService;

    public LangChainToolSearchAdapter() {
        this(new ToolSearchService(SimpleToolSearchStrategy.builder().build()));
    }

    LangChainToolSearchAdapter(ToolSearchService toolSearchService) {
        this.toolSearchService = toolSearchService;
    }

    public ToolServiceContext adjust(ToolServiceContext baseContext,
                                     ChatMemory chatMemory,
                                     InvocationContext invocationContext) {
        if (baseContext.availableTools().stream().allMatch(this::alwaysVisible)) {
            return baseContext;
        }
        List<ChatMessage> messages = chatMemory == null ? List.of() : chatMemory.messages();
        ToolServiceContext adjusted = toolSearchService.adjust(baseContext, messages, invocationContext);
        // 所有可查找工具已出现在本轮时，避免继续展示一个空目录。
        var visibleNames = adjusted.effectiveTools().stream().map(ToolSpecification::name)
                .collect(Collectors.toSet());
        return baseContext.availableTools().stream().allMatch(tool -> visibleNames.contains(tool.name()))
                ? baseContext : adjusted;
    }

    private boolean alwaysVisible(ToolSpecification tool) {
        return tool.metadata().get(METADATA_SEARCH_BEHAVIOR) == ALWAYS_VISIBLE;
    }
}
