package com.schemaplexai.service.user;

import com.schemaplexai.common.enums.UserMemoryKindEnum;
import com.schemaplexai.common.enums.UserMemoryScopeEnum;
import com.schemaplexai.common.enums.UserMemorySourceTypeEnum;
import com.schemaplexai.common.enums.UserMemoryStatusEnum;
import com.schemaplexai.dao.mapper.UserMemoryMapper;
import com.schemaplexai.dao.mapper.UserMemoryProfileMapper;
import com.schemaplexai.dao.mapper.UserMemorySettingMapper;
import com.schemaplexai.model.dto.user.UserMemoryCreateRequest;
import com.schemaplexai.model.entity.UserMemory;
import com.schemaplexai.model.entity.UserMemorySetting;
import com.schemaplexai.service.user.impl.UserMemoryServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserMemoryServiceImplTest {

    @Test
    void shouldCreateExplicitMemoryAsActiveForCurrentUser() {
        UserMemoryMapper memoryMapper = mock(UserMemoryMapper.class);
        UserMemorySettingMapper settingMapper = mock(UserMemorySettingMapper.class);
        UserMemoryProfileMapper profileMapper = mock(UserMemoryProfileMapper.class);
        when(memoryMapper.insert(any(UserMemory.class))).thenAnswer(invocation -> {
            UserMemory memory = invocation.getArgument(0);
            memory.setId("memory-1");
            return 1;
        });

        UserMemoryServiceImpl service = new UserMemoryServiceImpl(memoryMapper, settingMapper, profileMapper);
        UserMemoryCreateRequest request = new UserMemoryCreateRequest();
        request.setAgentId("agent-1");
        request.setMemoryScope(UserMemoryScopeEnum.USER_AGENT.getCode());
        request.setMemoryKind(UserMemoryKindEnum.PREFERENCE.getCode());
        request.setContent("用户偏好默认使用简体中文输出");
        request.setPinned(true);

        UserMemory memory = service.createExplicitMemory("tenant-1", "user-1", request);

        assertThat(memory.getTenantId()).isEqualTo("tenant-1");
        assertThat(memory.getUserId()).isEqualTo("user-1");
        assertThat(memory.getAgentId()).isEqualTo("agent-1");
        assertThat(memory.getSourceType()).isEqualTo(UserMemorySourceTypeEnum.EXPLICIT.getCode());
        assertThat(memory.getStatus()).isEqualTo(UserMemoryStatusEnum.ACTIVE.getCode());
        assertThat(memory.getPinned()).isTrue();
        assertThat(memory.getContentHash()).isNotBlank();
    }

    @Test
    void shouldSkipMemoryInjectionWhenSettingDisablesSavedMemory() {
        UserMemoryMapper memoryMapper = mock(UserMemoryMapper.class);
        UserMemorySettingMapper settingMapper = mock(UserMemorySettingMapper.class);
        UserMemoryProfileMapper profileMapper = mock(UserMemoryProfileMapper.class);
        UserMemorySetting setting = new UserMemorySetting();
        setting.setMemoryEnabled(true);
        setting.setReferenceSavedMemory(false);
        when(settingMapper.selectOne(any())).thenReturn(setting);

        UserMemoryServiceImpl service = new UserMemoryServiceImpl(memoryMapper, settingMapper, profileMapper);

        List<UserMemory> memories = service.findStaticMemories("tenant-1", "user-1", "agent-1", null, 10);

        assertThat(memories).isEmpty();
    }
}
