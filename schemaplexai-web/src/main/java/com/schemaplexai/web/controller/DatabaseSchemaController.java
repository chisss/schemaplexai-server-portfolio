package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.semantic.SchemaScanRequest;
import com.schemaplexai.model.vo.semantic.SchemaSnapshotVO;
import com.schemaplexai.service.semantic.application.command.ScanSchemaCommand;
import com.schemaplexai.service.semantic.application.orchestration.SchemaScanApplicationService;
import com.schemaplexai.service.semantic.domain.model.SchemaSnapshot;
import com.schemaplexai.web.mapper.SemanticWebMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 已连接数据源的 Schema 扫描与快照入口。 */
@Validated
@RestController
@RequestMapping("/database-sources")
@RequiredArgsConstructor
public class DatabaseSchemaController {

    private final SchemaScanApplicationService scanService;
    private final SemanticWebMapper mapper;

    @PostMapping("/{id}/schema/scan")
    @PreAuthorize("@authorizationService.hasPermission('database:source:manage')")
    public R<SchemaSnapshotVO> scan(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody(required = false) SchemaScanRequest request) {
        ScanSchemaCommand command = request == null
                ? null
                : new ScanSchemaCommand(request.catalog(), request.schema(), request.includeObjects());
        SchemaSnapshot snapshot = scanService.scan(id, command);
        List<SchemaSnapshot> history = scanService.list(id);
        boolean driftDetected = history.stream()
                .anyMatch(item -> !item.getFingerprint().equals(snapshot.getFingerprint()));
        return R.ok(mapper.toSnapshot(snapshot, driftDetected));
    }

    @GetMapping("/{id}/schema/snapshots")
    @PreAuthorize("@authorizationService.hasPermission('database:source:view')")
    public R<List<SchemaSnapshotVO>> snapshots(@PathVariable @NotBlank String id) {
        List<SchemaSnapshot> snapshots = scanService.list(id);
        String latestFingerprint = snapshots.isEmpty() ? null : snapshots.getFirst().getFingerprint();
        return R.ok(snapshots.stream()
                .map(snapshot -> mapper.toSnapshot(
                        snapshot,
                        latestFingerprint != null && !latestFingerprint.equals(snapshot.getFingerprint())))
                .toList());
    }
}
