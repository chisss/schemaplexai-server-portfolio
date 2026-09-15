package com.schemaplexai.service.database.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.enums.McpServerTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.McpServerMapper;
import com.schemaplexai.model.dto.database.DatabaseSourceQueryRequest;
import com.schemaplexai.model.dto.database.DatabaseSourceSaveRequest;
import com.schemaplexai.model.dto.mcp.McpServerCreateRequest;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.model.vo.mcp.McpServerVO;
import com.schemaplexai.service.database.credential.DatabaseCredentialVault;
import com.schemaplexai.service.integration.mcp.DatabaseMcpPresetResolver;
import com.schemaplexai.service.integration.mcp.McpClientService;
import com.schemaplexai.service.mcp.McpServerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatabaseSourceServiceImplTest {

    private final McpServerMapper mcpServerMapper = mock(McpServerMapper.class);
    private final McpServerService mcpServerService = mock(McpServerService.class);
    private final DatabaseCredentialVault credentialVault = mock(DatabaseCredentialVault.class);
    private final DatabaseSourceServiceImpl service = new DatabaseSourceServiceImpl(
            mcpServerMapper,
            mcpServerService,
            mock(McpClientService.class),
            new DatabaseMcpPresetResolver(),
            credentialVault,
            new ObjectMapper()
    );

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
    }

    @Test
    void shouldRejectDatabaseSourceAccessWithoutTenantContext() {
        assertThatThrownBy(() -> service.getById("source-1"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode());
                    assertThat(exception.getMessage()).isEqualTo("租户上下文缺失");
                });

        verifyNoInteractions(mcpServerMapper);
    }

    @Test
    void shouldLoadDatabaseSourceWithExplicitTenantAndTypeConditions() {
        SecurityUtil.setCurrentTenantId(" tenant-1 ");
        McpServer source = new McpServer();
        source.setId("source-1");
        source.setTenantId("tenant-1");
        source.setServerType(McpServerTypeEnum.DATABASE.getCode());
        when(mcpServerMapper.selectOne(any())).thenReturn(source);

        var result = service.getById("source-1");

        assertThat(result.getId()).isEqualTo("source-1");
        ArgumentCaptor<LambdaQueryWrapper<McpServer>> captor = wrapperCaptor();
        verify(mcpServerMapper).selectOne(captor.capture());
        assertThat(captor.getValue()).isNotNull();
    }

    @Test
    void shouldScopeDatabaseSourceListToCurrentTenant() {
        SecurityUtil.setCurrentTenantId("tenant-2");
        when(mcpServerMapper.selectList(any())).thenReturn(List.of());

        service.page(new DatabaseSourceQueryRequest());

        ArgumentCaptor<LambdaQueryWrapper<McpServer>> captor = wrapperCaptor();
        verify(mcpServerMapper).selectList(captor.capture());
        assertThat(captor.getValue()).isNotNull();
    }

    @Test
    void shouldPersistSecretRefWithoutPlainCredential() {
        SecurityUtil.setCurrentTenantId("tenant-1");
        DatabaseSourceSaveRequest request = new DatabaseSourceSaveRequest();
        request.setName("orders");
        request.setDatabaseType("postgresql");
        request.setPresetCode("postgresql");
        request.setDatabase("orders");
        request.setUsername("readonly");
        request.setPassword("plain-secret");
        when(credentialVault.save(any(), any())).thenReturn("secret-1");

        McpServerVO created = new McpServerVO();
        created.setId("source-1");
        when(mcpServerService.create(any())).thenReturn(created);
        McpServer stored = new McpServer();
        stored.setId("source-1");
        stored.setTenantId("tenant-1");
        stored.setServerType(McpServerTypeEnum.DATABASE.getCode());
        stored.setConnectionConfig(java.util.Map.of("secretRef", "secret-1"));
        when(mcpServerMapper.selectOne(any())).thenReturn(stored);

        service.create(request);

        ArgumentCaptor<McpServerCreateRequest> captor = ArgumentCaptor.forClass(McpServerCreateRequest.class);
        verify(mcpServerService).create(captor.capture());
        assertThat(captor.getValue().getConnectionConfig())
                .containsEntry("secretRef", "secret-1")
                .doesNotContainKeys("password", "connectionUri");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<LambdaQueryWrapper<McpServer>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    }
}
