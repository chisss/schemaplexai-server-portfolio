package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.application.command.ScanSchemaCommand;
import com.schemaplexai.service.semantic.domain.model.SchemaSnapshot;
import com.schemaplexai.service.semantic.domain.model.schema.DatabaseSchema;
import com.schemaplexai.service.semantic.domain.port.SchemaIntrospectorPort;
import com.schemaplexai.service.semantic.domain.port.SchemaMetadataSession;
import com.schemaplexai.service.semantic.domain.port.SchemaMetadataSessionFactory;
import com.schemaplexai.service.semantic.domain.repository.SchemaSnapshotRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaScanApplicationServiceTest {

    @Mock
    private SchemaMetadataSessionFactory sessionFactory;
    @Mock
    private SchemaSnapshotRepository repository;
    @Mock
    private SchemaIntrospectorPort introspector;
    @Mock
    private SchemaMetadataSession session;

    private SchemaScanApplicationService service;

    @BeforeEach
    void setUp() {
        service = new SchemaScanApplicationService(sessionFactory, repository, List.of(introspector));
        SecurityUtil.setCurrentTenantId("tenant-a");
    }

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
    }

    @Test
    void scansOwnedSourceAndPersistsTenantSnapshot() {
        DatabaseSchema schema = new DatabaseSchema("postgresql", null, "public", List.of(), Set.of(), List.of());
        when(sessionFactory.open("tenant-a", "source-1")).thenReturn(session);
        when(session.databaseType()).thenReturn("postgresql");
        when(introspector.supports("postgresql")).thenReturn(true);
        when(introspector.scan(any(), any())).thenReturn(schema);

        SchemaSnapshot result = service.scan("source-1", new ScanSchemaCommand(null, null, null));

        ArgumentCaptor<SchemaSnapshot> captor = ArgumentCaptor.forClass(SchemaSnapshot.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("tenant-a");
        assertThat(result.getFingerprint()).hasSize(64);
    }

    @Test
    void reusesExistingSnapshotForSameFingerprint() {
        DatabaseSchema schema = new DatabaseSchema("postgresql", null, "public", List.of(), Set.of(), List.of());
        SchemaSnapshot existing = SchemaSnapshot.create(
                "snapshot-1", "tenant-a", "source-1", new com.schemaplexai.service.semantic.domain.service.SchemaFingerprint().calculate(schema),
                schema, java.time.LocalDateTime.now());
        when(sessionFactory.open("tenant-a", "source-1")).thenReturn(session);
        when(session.databaseType()).thenReturn("postgresql");
        when(introspector.supports("postgresql")).thenReturn(true);
        when(introspector.scan(any(), any())).thenReturn(schema);
        when(repository.findByFingerprint(any(), any(), any())).thenReturn(Optional.of(existing));

        assertThat(service.scan("source-1", null)).isSameAs(existing);
        verify(repository, never()).insert(any());
    }

    @Test
    void rejectsMissingTenantBeforeOpeningSource() {
        SecurityUtil.clear();

        assertThatThrownBy(() -> service.scan("source-1", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
        verify(sessionFactory, never()).open(any(), any());
    }

    @Test
    void rejectsUnsupportedDatabaseType() {
        when(sessionFactory.open("tenant-a", "source-1")).thenReturn(session);
        when(session.databaseType()).thenReturn("oracle");
        when(introspector.supports("oracle")).thenReturn(false);

        assertThatThrownBy(() -> service.scan("source-1", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ResultCode.SCHEMA_SOURCE_UNSUPPORTED.getCode()));
    }
}
