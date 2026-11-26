package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.domain.model.SchemaSnapshot;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration.Concept;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration.Property;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfigurationWorkspace;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyQueryProfile;
import com.schemaplexai.service.semantic.domain.model.schema.DatabaseSchema;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaField;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaForeignKey;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObject;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObjectType;
import com.schemaplexai.service.semantic.domain.port.OntologySuggestionPort;
import com.schemaplexai.service.semantic.domain.port.OntologySuggestionPort.Suggestion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class OntologySuggestionApplicationServiceTest {

    private final OntologyConfigurationApplicationService configurations = mock(OntologyConfigurationApplicationService.class);
    private final SchemaScanApplicationService schema = mock(SchemaScanApplicationService.class);
    private final OntologySuggestionPort assistant = mock(OntologySuggestionPort.class);
    private final OntologySuggestionApplicationService service = new OntologySuggestionApplicationService(configurations, schema, assistant);

    @AfterEach
    void clearContext() {
        SecurityUtil.clear();
    }

    @Test
    void handsModelOnlySelectedStructureAndRestoresHiddenExistingMappingsBeforeValidation() {
        SecurityUtil.setCurrentTenantId("tenant");
        var previous = config("private.accounting", "sensitive_column");
        var profile = new OntologyQueryProfile("id", "Asia/Shanghai",
                List.of(new OntologyQueryProfile.Measure("column", "count")), List.of(), List.of(), null);
        var saved = new OntologyConfiguration(previous.classes(), previous.properties(), previous.relationships(), profile);
        when(configurations.load("version")).thenReturn(new OntologyConfigurationWorkspace(4, true, saved, "source", "snapshot", null));
        var field = new SchemaField("id", "bigint", false, "这个备注不发送", Map.of("bigint", 37L));
        var visible = new SchemaObject("sales", "orders", SchemaObjectType.TABLE, "此表声明不发送", "engine", null,
                List.of(field), List.of("id"), List.of(new SchemaForeignKey("secret_link", List.of("id"),
                "private", "accounting", List.of("sensitive_column"))), List.of());
        var hidden = new SchemaObject("private", "accounting", SchemaObjectType.TABLE, null, null, null,
                List.of(field), List.of("id"), List.of(), List.of());
        var metadata = new DatabaseSchema("postgresql", "database", "sales", List.of(visible, hidden), Set.of(), List.of());
        when(schema.getSnapshot("source", "snapshot"))
                .thenReturn(SchemaSnapshot.create("snapshot", "tenant", "source", "fingerprint", metadata, LocalDateTime.now()));
        var proposed = config(null, null);
        var untrustedProfile = new OntologyQueryProfile("untrusted", "UTC", List.of(), List.of(), List.of(), null);
        when(assistant.generate(eq("tenant"), any())).thenReturn(new Suggestion(
                new OntologyConfiguration(proposed.classes(), proposed.properties(), proposed.relationships(), untrustedProfile),
                "核对定义", List.of()));

        Suggestion result = service.suggest("version", 4, "确认工单", List.of("sales.orders"));

        var captured = ArgumentCaptor.forClass(OntologySuggestionPort.Request.class);
        verify(assistant).generate(eq("tenant"), captured.capture());
        assertThat(captured.getValue().configuration()).isEqualTo(config(null, null));
        assertThat(captured.getValue().selectedSchema()).singleElement().satisfies(object -> {
            assertThat(object.key()).isEqualTo("sales.orders");
            assertThat(object.foreignKeys()).isEmpty();
            assertThat(object.columns()).containsExactly(new OntologySuggestionPort.Column("id", "bigint", false));
        });
        assertThat(result.configuration()).isEqualTo(saved);
        verify(configurations).load("version");
        verify(configurations).verifyCandidate("version", 4, saved);
        verifyNoMoreInteractions(configurations);
    }

    @Test
    void connectedSuggestionStopsBeforeModelWhenSourceAccessIsDenied() {
        when(configurations.load("version")).thenReturn(new OntologyConfigurationWorkspace(
                4, true, OntologyConfiguration.empty(), "source", "snapshot", null));
        when(schema.getSnapshot("source", "snapshot")).thenThrow(new BusinessException(ResultCode.FORBIDDEN));

        assertThatThrownBy(() -> service.suggest("version", 4, "读取本体", List.of("sales.orders")))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(assistant);
    }

    @Test
    void rejectsAnInventedMappingInSourceFreeDraft() {
        when(configurations.load("version")).thenReturn(new OntologyConfigurationWorkspace(
                4, true, OntologyConfiguration.empty(), null, null, null));
        when(assistant.generate(any(), any())).thenReturn(new Suggestion(config("unknown.table", null), "猜测表", List.of()));
        SecurityUtil.setCurrentTenantId("tenant");

        assertThatThrownBy(() -> service.suggest("version", 4, "定义客户", List.of()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未选中的 Schema");
        verifyNoInteractions(schema);
    }

    private OntologyConfiguration config(String object, String field) {
        return new OntologyConfiguration(List.of(new Concept("id", "Ticket", "工单", null, object)),
                List.of(new Property("column", "id", "Field", "字段", "integer", false, field)), List.of());
    }
}
