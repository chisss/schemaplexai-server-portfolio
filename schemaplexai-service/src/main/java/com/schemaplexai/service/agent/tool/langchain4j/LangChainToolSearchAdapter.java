package com.schemaplexai.service.agent.tool.langchain4j;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.tool.ToolServiceContext;
import dev.langchain4j.service.tool.search.ToolSearchService;
import dev.langchain4j.service.tool.search.simple.SimpleToolSearchStrategy;

import java.util.List;

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
        List<ChatMessage> messages = chatMemory == null ? List.of() : chatMemory.messages();
        return toolSearchService.adjust(baseContext, messages, invocationContext);
    }
}
