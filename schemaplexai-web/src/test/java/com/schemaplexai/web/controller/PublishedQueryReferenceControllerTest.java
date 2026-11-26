package com.schemaplexai.web.controller;

import com.schemaplexai.service.semantic.application.orchestration.PublishedQueryReferenceApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.PublishedQueryReferenceApplicationService.Register;
import com.schemaplexai.service.semantic.domain.model.query.PublishedQueryReference;
import com.schemaplexai.web.controller.PublishedQueryReferenceController.ReferenceRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishedQueryReferenceControllerTest {

    @Test
    void exposesScopedManagementEndpointsAndValidatesRequest() throws NoSuchMethodException {
        var controller = PublishedQueryReferenceController.class;
        assertThat(controller.getAnnotation(RequestMapping.class).value())
                .containsExactly("/semantic/query/datasets/{datasetId}/reconciliation");
        assertThat(controller.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("@authorizationService.hasPermission('database:source:manage')");
        assertThat(controller.getMethod("get", String.class).isAnnotationPresent(GetMapping.class)).isTrue();
        assertThat(controller.getMethod("put", String.class, ReferenceRequest.class).isAnnotationPresent(PutMapping.class))
                .isTrue();
        assertThat(controller.getMethod("delete", String.class).isAnnotationPresent(DeleteMapping.class)).isTrue();

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new ReferenceRequest("", "", "x".repeat(257), "x".repeat(64),
                    List.of(new PublishedQueryReferenceController.DimensionRequest("", "")), "x".repeat(64),
                    "1".repeat(40), "");
            assertThat(factory.getValidator().validate(request)).hasSize(9);
        }
    }

    @Test
    void managementResponseKeepsExactToleranceAndOmitsInternalBinding() {
        var service = mock(PublishedQueryReferenceApplicationService.class);
        var controller = new PublishedQueryReferenceController(service);
        var dimensions = List.of(new PublishedQueryReferenceApplicationService.DimensionMapping("status", "status"));
        var command = new Register("amount", "sum", "accounting.reference", "total",
                "0.000000000000000001", "财务参考", dimensions, "event_time");
        var reference = new PublishedQueryReference("ds", "source", "reporting.orders", "version", "checksum",
                "snapshot", "sourceFingerprint", "amount", "urn:amount", "sum", "accounting.reference", "total",
                new BigDecimal(command.tolerance()), command.label(), "internal-reference", "source-revision", "amount",
                List.of(new PublishedQueryReference.Dimension("status", "urn:status", "status", "string",
                        "character varying", "status")),
                new PublishedQueryReference.Time("eventTime", "urn:eventTime", "created_at", "dateTime",
                        "timestamp with time zone", "America/New_York", "event_time"));
        when(service.register("ds", command)).thenReturn(reference);
        when(service.get("ds")).thenReturn(Optional.empty(), Optional.of(reference));
        assertThat(controller.get("ds").getData()).isNull();
        var saved = controller.put("ds", new ReferenceRequest(command.measurePropertyId(), command.aggregation(),
                command.referenceObject(), command.referenceColumn(),
                List.of(new PublishedQueryReferenceController.DimensionRequest("status", "status")),
                "event_time", command.tolerance(), command.label())).getData();
        assertThat(saved.tolerance()).isEqualTo("0.000000000000000001");
        assertThat(saved.referenceObject()).isEqualTo("accounting.reference");
        assertThat(saved.dimensions()).containsExactly(
                new PublishedQueryReferenceController.DimensionRequest("status", "status"));
        assertThat(saved.timeReferenceColumn()).isEqualTo("event_time");
        assertThat(saved.toString()).doesNotContain("internal-reference", "checksum", "source-revision", "urn:amount");
        assertThat(controller.get("ds").getData()).isEqualTo(saved);
        assertThat(controller.delete("ds").getCode()).isEqualTo(200);
        verify(service).remove("ds");
    }
}
