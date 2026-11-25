package com.schemaplexai.service.memory;

import com.schemaplexai.service.memory.PostgresChatMemoryStore.ArchivedMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.List;

/** 一个执行实例使用独立模型窗口，原始消息按执行写入会话轨迹。 */
public final class ArchivedChatMemory implements ChatMemory {

    private final String conversationId;
    private final String executionId;
    private final CompositeChatMemoryStore store;
    private final ChatMemory window;
    private List<ChatMessage> snapshot;

    public ArchivedChatMemory(String conversationId, String executionId, CompositeChatMemoryStore store,
                              ChatMemory window, int replayLimit) {
        this.conversationId = conversationId;
        this.executionId = executionId;
        this.store = store;
        this.window = window;
        set(store.loadWindow(conversationId, executionId, replayLimit));
    }

    @Override
    public Object id() {
        return window.id();
    }

    public void addUserInput(UserMessage message, String rawInput) {
        store.appendMessages(conversationId, executionId, List.of(new ArchivedMessage(message, rawInput)));
        addArchivedUserInput(message);
    }

    /** 已持久化的原始用户输入只需要注入当前模型窗口。 */
    public void addArchivedUserInput(UserMessage message) {
        window.add(message);
        refreshSnapshot();
    }

    @Override
    public void add(ChatMessage message) {
        add(List.of(message));
    }

    @Override
    public void add(Iterable<ChatMessage> messages) {
        List<ArchivedMessage> entries = new ArrayList<>();
        for (ChatMessage message : messages) {
            entries.add(new ArchivedMessage(message, null));
        }
        if (entries.isEmpty()) {
            return;
        }
        store.appendMessages(conversationId, executionId, entries);
        for (ArchivedMessage entry : entries) {
            window.add(entry.message());
        }
        refreshSnapshot();
    }

    /** 审批的模型视图可重建，原始轨迹需要同时保留新增的工具结果。 */
    public void recordResumedToolResults(List<ToolExecutionResultMessage> results) {
        store.appendMissingToolResults(conversationId, executionId, results);
    }

    @Override
    public void set(Iterable<ChatMessage> messages) {
        window.set(messages);
        refreshSnapshot();
    }

    @Override
    public List<ChatMessage> messages() {
        return snapshot;
    }

    @Override
    public void clear() {
        window.clear();
        refreshSnapshot();
    }

    private void refreshSnapshot() {
        snapshot = List.copyOf(window.messages());
    }
}
