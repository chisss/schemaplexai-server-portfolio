package com.schemaplexai.service.user;

import com.schemaplexai.common.enums.UserMemoryKindEnum;
import com.schemaplexai.common.enums.UserMemoryScopeEnum;
import com.schemaplexai.common.enums.UserMemorySourceTypeEnum;
import com.schemaplexai.common.enums.UserMemoryStatusEnum;
import com.schemaplexai.dao.mapper.ChatMessageMapper;
import com.schemaplexai.dao.mapper.UserMemoryMapper;
import com.schemaplexai.dao.mapper.UserMemorySettingMapper;
import com.schemaplexai.model.entity.UserMemory;
import com.schemaplexai.model.entity.UserMemorySetting;
import com.schemaplexai.service.memory.MemoryExtractionWriter;
import com.schemaplexai.service.user.impl.UserMemoryExtractionService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserMemoryExtractionServiceTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "project-1")
    void shouldExtractExplicitPreferenceFromUserInstruction(String projectId) {
        UserMemoryMapper memoryMapper = mock(UserMemoryMapper.class);
        UserMemorySettingMapper settingMapper = mock(UserMemorySettingMapper.class);
        when(memoryMapper.insert(any(UserMemory.class))).thenReturn(1);

        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        when(messageMapper.claimMemoryExtraction("execution-1", "USER")).thenReturn(1, 0);
        UserMemoryExtractionService service = new UserMemoryExtractionService(
                memoryMapper, settingMapper, new MemoryExtractionWriter(messageMapper));
        List<ChatMessage> history = List.of(
                UserMessage.from("请记住：以后默认用简体中文回复，代码注释使用中文。")
        );

        List<UserMemory> memories = service.extractMemories(
                "tenant-1", "user-1", "agent-1", projectId, "execution-1", "conversation-1", history, false);

        assertThat(memories).hasSize(1);
        UserMemory memory = memories.getFirst();
        assertThat(memory.getTenantId()).isEqualTo("tenant-1");
        assertThat(memory.getUserId()).isEqualTo("user-1");
        assertThat(memory.getAgentId()).isEqualTo("agent-1");
        boolean projectScoped = "project-1".equals(projectId);
        assertThat(memory.getProjectId()).isEqualTo(projectScoped ? projectId : null);
        assertThat(memory.getMemoryScope()).isEqualTo((projectScoped ? UserMemoryScopeEnum.USER_PROJECT
                : UserMemoryScopeEnum.USER_AGENT).getCode());
        assertThat(memory.getSourceType()).isEqualTo(UserMemorySourceTypeEnum.EXPLICIT.getCode());
        assertThat(memory.getStatus()).isEqualTo(UserMemoryStatusEnum.ACTIVE.getCode());
        assertThat(memory.getMemoryKind()).isEqualTo(UserMemoryKindEnum.PREFERENCE.getCode());
        assertThat(memory.getContent()).contains("简体中文", "中文");
        assertThat(service.extractMemories("tenant-1", "user-1", "agent-1", projectId, "execution-1", "conversation-1",
                history, false)).isEmpty();
        verify(memoryMapper).insert(any(UserMemory.class));
    }

    @Test
    void shouldSkipTemporaryChat() {
        UserMemoryExtractionService service = new UserMemoryExtractionService(
                mock(UserMemoryMapper.class), mock(UserMemorySettingMapper.class), mock(MemoryExtractionWriter.class));

        List<UserMemory> memories = service.extractMemories(
                "tenant-1", "user-1", "agent-1", null, "execution-1", "conversation-1",
                List.of(UserMessage.from("记住：我偏好表格输出。")), true);

        assertThat(memories).isEmpty();
    }

    @Test
    void shouldSaveExplicitEnglishAndPreserveNegativePreference() {
        UserMemoryMapper mapper = mock(UserMemoryMapper.class);
        when(mapper.insert(any(UserMemory.class))).thenReturn(1);
        ChatMessageMapper messages = mock(ChatMessageMapper.class);
        when(messages.claimMemoryExtraction("run", "USER")).thenReturn(1);
        var service = new UserMemoryExtractionService(mapper, mock(UserMemorySettingMapper.class),
                new MemoryExtractionWriter(messages));

        var saved = service.extractMemories("tenant", "user", "agent", null, "run", "conversation", List.of(
                AiMessage.from("Remember: do not record assistant text."),
                UserMessage.from("Summarize this quoted sentence: remember the handoff."),
                UserMessage.from("Prepare a release summary.\nPlease remember that I prefer CSV reports."),
                UserMessage.from("请不要再把构建日志贴到交接文档里。")), false);

        assertThat(saved).extracting(UserMemory::getContent)
                .containsExactly("I prefer CSV reports.", "不要再把构建日志贴到交接文档里。");
        assertThat(saved.getLast().getMemoryKind()).isEqualTo(UserMemoryKindEnum.AVOIDANCE.getCode());
    }

    @Test
    void shouldSkipHistoryReadWhenExecutionPolicyDisallowsSaving() {
        UserMemoryMapper mapper = mock(UserMemoryMapper.class);
        UserMemorySettingMapper settings = mock(UserMemorySettingMapper.class);
        MemoryExtractionWriter writer = mock(MemoryExtractionWriter.class);
        var service = new UserMemoryExtractionService(mapper, settings, writer);

        service.saveAfterCompletion("tenant", "user", "agent", null, "run", "conversation", true, true,
                () -> { throw new AssertionError("临时会话不应读取提取来源"); });
        service.saveAfterCompletion("tenant", "user", "agent", null, "run", "conversation", false, false,
                () -> { throw new AssertionError("禁写会话不应读取提取来源"); });

        var disabled = new UserMemorySetting();
        disabled.setMemoryEnabled(true);
        disabled.setAutoExtractEnabled(false);
        when(settings.selectOne(any())).thenReturn(disabled);
        service.saveAfterCompletion("tenant", "user", "agent", null, "run", "conversation", false, null,
                () -> List.of(UserMessage.from("请记住：以后默认用简体中文回复。")));
        verifyNoInteractions(mapper, writer);
    }
}
