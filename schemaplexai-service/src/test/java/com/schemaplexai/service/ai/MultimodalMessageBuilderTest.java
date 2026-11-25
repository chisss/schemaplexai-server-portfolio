package com.schemaplexai.service.ai;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.service.desktop.DesktopAttachmentService;
import com.schemaplexai.service.storage.DocumentStorageService;
import com.schemaplexai.service.util.FileContentExtractor;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MultimodalMessageBuilderTest {
    private static final String KEY = "tenants/tenant/chat/2026/11/21/id/picture.png";
    private final DesktopAttachmentService attachments = mock(DesktopAttachmentService.class);
    private final DocumentStorageService storage = mock(DocumentStorageService.class);
    private final MultimodalMessageBuilder builder = new MultimodalMessageBuilder(
            attachments, storage, new FileContentExtractor());

    @Test
    void shouldRecheckQueuedAttachmentAuthorizationBeforeStorageRead() {
        doThrow(new BusinessException(ResultCode.FORBIDDEN))
                .when(attachments).validateObjectKeys(List.of(KEY), false, "tenant", "owner");

        assertThatThrownBy(() -> builder.buildForExecution("test", List.of(KEY), true, "tenant", "owner", "conv"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void shouldSendVerifiedImageInNativeMessage() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", bytes);
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(storage.getObject("bucket", KEY)).thenReturn(new ByteArrayInputStream(bytes.toByteArray()));

        var message = builder.buildForExecution("test", List.of(KEY), true, "tenant", "owner", "conv");

        assertThat(message.contents()).hasSize(2);
        assertThat(message.contents().get(0)).isInstanceOf(TextContent.class);
        assertThat(message.contents().get(1)).isInstanceOf(ImageContent.class);
        InOrder order = inOrder(attachments, storage);
        order.verify(attachments).validateObjectKeys(List.of(KEY), false, "tenant", "owner");
        order.verify(storage).getObject("bucket", KEY);
    }

    @Test
    void shouldRejectUnsupportedContentBeforeNativeImageEncoding() {
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(storage.getObject("bucket", KEY)).thenReturn(new ByteArrayInputStream(new byte[]{'t', 'e', 'x', 't'}));

        assertThatThrownBy(() -> builder.buildForExecution("test", List.of(KEY), true, "tenant", "owner", "conv"))
                .isInstanceOf(BusinessException.class);
    }
}
