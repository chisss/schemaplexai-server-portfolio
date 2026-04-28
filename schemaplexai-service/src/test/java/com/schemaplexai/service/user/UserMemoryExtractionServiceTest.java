package com.schemaplexai.service.user;

import com.schemaplexai.common.enums.UserMemoryKindEnum;
import com.schemaplexai.common.enums.UserMemorySourceTypeEnum;
import com.schemaplexai.common.enums.UserMemoryStatusEnum;
import com.schemaplexai.dao.mapper.UserMemoryMapper;
import com.schemaplexai.dao.mapper.UserMemorySettingMapper;
import com.schemaplexai.model.entity.UserMemory;
import com.schemaplexai.service.user.impl.UserMemoryExtractionService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserMemoryExtractionServiceTest {

    @Test
    void shouldExtractExplicitPreferenceFromUserInstruction() {
        UserMemoryMapper memoryMapper = mock(UserMemoryMapper.class);
        UserMemorySettingMapper settingMapper = mock(UserMemorySettingMapper.class);
        when(memoryMapper.insert(any(UserMemory.class))).thenReturn(1);

        UserMemoryExtractionService service = new UserMemoryExtractionService(memoryMapper, settingMapper);
        List<ChatMessage> history = List.of(
                UserMessage.from("请记住：以后默认用简体中文回复，代码注释使用中文。")
        );

        List<UserMemory> memories = service.extractMemories(
                "tenant-1", "user-1", "agent-1", "execution-1", "conversation-1", history, false);

        assertThat(memories).hasSize(1);
        UserMemory memory = memories.getFirst();
        assertThat(memory.getTenantId()).isEqualTo("tenant-1");
        assertThat(memory.getUserId()).isEqualTo("user-1");
        assertThat(memory.getAgentId()).isEqualTo("agent-1");
        assertThat(memory.getSourceType()).isEqualTo(UserMemorySourceTypeEnum.EXPLICIT.getCode());
        assertThat(memory.getStatus()).isEqualTo(UserMemoryStatusEnum.ACTIVE.getCode());
        assertThat(memory.getMemoryKind()).isEqualTo(UserMemoryKindEnum.PREFERENCE.getCode());
        assertThat(memory.getContent()).contains("简体中文", "中文");
    }

    @Test
    void shouldSkipTemporaryChat() {
        UserMemoryExtractionService service = new UserMemoryExtractionService(
                mock(UserMemoryMapper.class), mock(UserMemorySettingMapper.class));

        List<UserMemory> memories = service.extractMemories(
                "tenant-1", "user-1", "agent-1", "execution-1", "conversation-1",
                List.of(UserMessage.from("记住：我偏好表格输出。")), true);

        assertThat(memories).isEmpty();
    }
}
