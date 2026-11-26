package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.service.semantic.application.orchestration.PublishedQueryReferenceApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.PublishedQueryReferenceApplicationService.DimensionMapping;
import com.schemaplexai.service.semantic.application.orchestration.PublishedQueryReferenceApplicationService.Register;
import com.schemaplexai.service.semantic.domain.model.query.PublishedQueryReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 数据集独立参考的管理入口。 */
@Validated
@RestController
@RequestMapping("/semantic/query/datasets/{datasetId}/reconciliation")
@PreAuthorize("@authorizationService.hasPermission('database:source:manage')")
@RequiredArgsConstructor
public class PublishedQueryReferenceController {

    private final PublishedQueryReferenceApplicationService references;

    public record ReferenceRequest(
            @NotBlank @Size(max = 100) String measurePropertyId,
            @NotBlank @Size(max = 8) String aggregation,
            @NotBlank @Size(max = 256) String referenceObject,
            @NotBlank @Size(max = 63) String referenceColumn,
            @Size(max = 3) List<@Valid DimensionRequest> dimensions,
            @Size(max = 63) String timeReferenceColumn,
            @Size(max = 39) String tolerance,
            @NotBlank @Size(max = 160) String label) {
    }

    public record DimensionRequest(@NotBlank @Size(max = 100) String propertyId,
                                   @NotBlank @Size(max = 63) String referenceColumn) {
    }

    public record ReferenceView(String datasetId, String versionId, String schemaSnapshotId,
                                String measurePropertyId, String aggregation, String referenceObject,
                                String referenceColumn, List<DimensionRequest> dimensions,
                                String timeReferenceColumn, String tolerance, String label) {
    }

    @GetMapping
    public R<ReferenceView> get(@PathVariable @NotBlank @Size(max = 128) String datasetId) {
        return R.ok(references.get(datasetId).map(this::view).orElse(null));
    }

    @PutMapping
    public R<ReferenceView> put(@PathVariable @NotBlank @Size(max = 128) String datasetId,
                                @Valid @RequestBody ReferenceRequest request) {
        var command = new Register(request.measurePropertyId(), request.aggregation(), request.referenceObject(),
                request.referenceColumn(), request.tolerance(), request.label(),
                request.dimensions() == null ? List.of() : request.dimensions().stream()
                        .map(dimension -> new DimensionMapping(dimension.propertyId(), dimension.referenceColumn())).toList(),
                request.timeReferenceColumn());
        return R.ok(view(references.register(datasetId, command)));
    }

    @DeleteMapping
    public R<Void> delete(@PathVariable @NotBlank @Size(max = 128) String datasetId) {
        references.remove(datasetId);
        return R.ok();
    }

    private ReferenceView view(PublishedQueryReference reference) {
        return new ReferenceView(reference.datasetId(), reference.versionId(), reference.schemaSnapshotId(),
                reference.measurePropertyId(), reference.aggregation(), reference.referenceView(),
                reference.referenceColumn(), reference.dimensions().stream()
                        .map(dimension -> new DimensionRequest(dimension.propertyId(), dimension.referenceColumn())).toList(),
                reference.time() == null ? null : reference.time().referenceColumn(),
                reference.tolerance().toPlainString(), reference.label());
    }
}
