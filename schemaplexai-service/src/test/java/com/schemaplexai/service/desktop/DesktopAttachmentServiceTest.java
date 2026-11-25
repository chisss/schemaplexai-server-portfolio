package com.schemaplexai.service.desktop;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.storage.DocumentStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DesktopAttachmentServiceTest {
    private static final String ID = "1ee62e90-c9ac-4d3e-9330-21afca68812e";
    private static final String WEB_KEY = "tenants/tenant-test/chat/2026/11/21/" + ID + "/note.txt";
    private static final String DESKTOP_KEY = "tenants/tenant-test/desktop-chat/" + ID + "/note.txt";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DocumentStorageService storage = mock(DocumentStorageService.class);
    private final DesktopAttachmentService service = new DesktopAttachmentService(jdbc, storage);

    @AfterEach
    void clearIdentity() {
        SecurityUtil.clear();
    }

    @Test
    void shouldAcceptRegisteredWebAttachmentForItsOwnerOnly() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(ID), eq(WEB_KEY),
                eq("tenant-test"), eq("owner"))).thenReturn(20L);
        service.validateObjectKeys(List.of(WEB_KEY), false, "tenant-test", "owner");

        assertThatThrownBy(() -> service.validateObjectKeys(List.of(WEB_KEY), false, "tenant-test", "another-owner"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldRejectKeysOutsideTheAuthenticatedChannelWithoutReadingStorage() {
        assertThatThrownBy(() -> service.validateObjectKeys(List.of(WEB_KEY), false, "other-tenant", "owner"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.validateObjectKeys(List.of(DESKTOP_KEY), false, "tenant-test", "owner"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(jdbc, storage);
    }

    @Test
    void shouldAcceptRegisteredDesktopAttachmentsForWorkspace() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(ID), eq(DESKTOP_KEY),
                eq("tenant-test"), eq("owner"))).thenReturn(20L);
        service.validateObjectKeys(List.of(DESKTOP_KEY), true, "tenant-test", "owner");
    }

    @Test
    void shouldRejectExpiredOrUnregisteredLegacyAttachment() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(ID), eq(WEB_KEY),
                eq("tenant-test"), eq("owner"))).thenReturn(-1L);
        assertThatThrownBy(() -> service.validateObjectKeys(List.of(WEB_KEY), false, "tenant-test", "owner"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void shouldRegisterWebUploadAndPreserveTheResponseContract() {
        SecurityUtil.setCurrentTenantId("tenant-test");
        SecurityUtil.setCurrentUserId("owner");
        when(storage.getDefaultBucket()).thenReturn("test-bucket");
        when(storage.getPresignedDownloadUrl(eq("test-bucket"), anyString(), eq(3600))).thenReturn("preview");
        byte[] bytes = {'n', 'o', 't', 'e'};

        var result = service.uploadChat("note.txt", new ByteArrayInputStream(bytes), bytes.length);

        assertThat(result.fileId()).startsWith("tenants/tenant-test/chat/").endsWith("/note.txt");
        assertThat(result.fileName()).isEqualTo("note.txt");
        assertThat(result.fileUrl()).isEqualTo("preview");
        verify(storage).putObject(eq("test-bucket"), eq(result.fileId()), any(InputStream.class),
                eq(4L), eq("text/plain"));
        verify(jdbc).update(anyString(), anyString(), eq("tenant-test"), eq("owner"), eq(result.fileId()),
                eq("note.txt"), eq("text/plain"), eq(4));
    }
}
