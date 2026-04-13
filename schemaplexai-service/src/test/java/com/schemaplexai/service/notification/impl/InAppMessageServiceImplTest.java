package com.schemaplexai.service.notification.impl;

import com.schemaplexai.common.enums.InAppMessageRecipientStatusEnum;
import com.schemaplexai.dao.mapper.InAppMessageMapper;
import com.schemaplexai.dao.mapper.InAppMessageRecipientMapper;
import com.schemaplexai.model.entity.InAppMessage;
import com.schemaplexai.model.entity.InAppMessageRecipient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InAppMessageServiceImplTest {

    @Test
    void shouldArchiveRecipientsBySource() {
        InAppMessageMapper messageMapper = mock(InAppMessageMapper.class);
        InAppMessageRecipientMapper recipientMapper = mock(InAppMessageRecipientMapper.class);
        InAppMessageServiceImpl service = new InAppMessageServiceImpl(messageMapper, recipientMapper);

        InAppMessage message = new InAppMessage();
        message.setId("msg-1");
        InAppMessageRecipient unreadRecipient = new InAppMessageRecipient();
        unreadRecipient.setId("recipient-1");
        unreadRecipient.setStatus(InAppMessageRecipientStatusEnum.UNREAD.getCode());
        InAppMessageRecipient readRecipient = new InAppMessageRecipient();
        readRecipient.setId("recipient-2");
        readRecipient.setStatus(InAppMessageRecipientStatusEnum.READ.getCode());

        when(messageMapper.selectList(any())).thenReturn(List.of(message));
        when(recipientMapper.selectList(any())).thenReturn(List.of(unreadRecipient, readRecipient));

        service.archiveBySource("workflow_human_review", "node-exec-1");

        ArgumentCaptor<InAppMessageRecipient> captor = ArgumentCaptor.forClass(InAppMessageRecipient.class);
        verify(recipientMapper, times(2)).updateById(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(recipient -> {
                    assertThat(recipient.getStatus()).isEqualTo(InAppMessageRecipientStatusEnum.ARCHIVED.getCode());
                    assertThat(recipient.getArchivedAt()).isNotNull();
                    assertThat(recipient.getReadAt()).isNotNull();
                });
    }

    @Test
    void shouldArchiveRecipientsByPendingReviewTarget() {
        InAppMessageMapper messageMapper = mock(InAppMessageMapper.class);
        InAppMessageRecipientMapper recipientMapper = mock(InAppMessageRecipientMapper.class);
        InAppMessageServiceImpl service = new InAppMessageServiceImpl(messageMapper, recipientMapper);

        InAppMessage message = new InAppMessage();
        message.setId("msg-2");
        InAppMessageRecipient unreadRecipient = new InAppMessageRecipient();
        unreadRecipient.setId("recipient-3");
        unreadRecipient.setStatus(InAppMessageRecipientStatusEnum.UNREAD.getCode());

        when(messageMapper.selectList(any())).thenReturn(List.of(message));
        when(recipientMapper.selectList(any())).thenReturn(List.of(unreadRecipient));

        service.archivePendingReviewMessages("tenant-1", "spec-1", "requirements_review", "node-exec-latest");

        ArgumentCaptor<InAppMessageRecipient> captor = ArgumentCaptor.forClass(InAppMessageRecipient.class);
        verify(recipientMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(InAppMessageRecipientStatusEnum.ARCHIVED.getCode());
        assertThat(captor.getValue().getArchivedAt()).isNotNull();
        assertThat(captor.getValue().getReadAt()).isNotNull();
    }
}
