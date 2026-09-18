package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.semantic.SemanticModelCreateRequest;
import com.schemaplexai.model.dto.semantic.SemanticModelUpdateRequest;
import com.schemaplexai.model.dto.semantic.SemanticVersionCreateRequest;
import com.schemaplexai.model.vo.semantic.SemanticModelVO;
import com.schemaplexai.model.vo.semantic.SemanticVersionVO;
import com.schemaplexai.service.semantic.application.command.CreateSemanticModelCommand;
import com.schemaplexai.service.semantic.application.command.UpdateSemanticModelCommand;
import com.schemaplexai.service.semantic.application.orchestration.SemanticModelApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.SemanticVersionApplicationService;
import com.schemaplexai.service.semantic.common.SemanticModelStatus;
import com.schemaplexai.service.semantic.domain.model.SemanticModel;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.web.mapper.SemanticWebMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/** 语义模型和版本控制面 REST 入口。 */
@Validated
@RestController
@RequestMapping("/semantic")
@RequiredArgsConstructor
public class SemanticModelController {

    private final SemanticModelApplicationService modelService;
    private final SemanticVersionApplicationService versionService;
    private final SemanticWebMapper mapper;

    @GetMapping("/models")
    @PreAuthorize("@authorizationService.hasPermission('semantic:model:view')")
    public R<PageResult<SemanticModelVO>> page(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String status) {
        PageResult<SemanticModel> result = modelService.page(
                page, size, keyword, domain, parseStatus(status));
        List<SemanticModelVO> records = result.getRecords().stream().map(this::toModel).toList();
        return R.ok(new PageResult<>(records, result.getTotal(), result.getCurrent(), result.getSize()));
    }

    @GetMapping("/models/{id}")
    @PreAuthorize("@authorizationService.hasPermission('semantic:model:view')")
    public R<SemanticModelVO> get(@PathVariable @NotBlank String id) {
        return R.ok(toModel(modelService.get(id)));
    }

    @PostMapping("/models")
    @PreAuthorize("@authorizationService.hasPermission('semantic:model:manage')")
    public R<SemanticModelVO> create(@Valid @RequestBody SemanticModelCreateRequest request) {
        SemanticModel model = modelService.create(new CreateSemanticModelCommand(
                request.name(), request.domain(), request.description()));
        return R.ok(toModel(model));
    }

    @PatchMapping("/models/{id}")
    @PreAuthorize("@authorizationService.hasPermission('semantic:model:manage')")
    public R<SemanticModelVO> update(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody SemanticModelUpdateRequest request) {
        SemanticModel model = modelService.update(id, new UpdateSemanticModelCommand(
                request.name(),
                request.domain(),
                request.description(),
                request.expectedRevision()));
        return R.ok(toModel(model));
    }

    @DeleteMapping("/models/{id}")
    @PreAuthorize("@authorizationService.hasPermission('semantic:model:manage')")
    public R<Void> delete(@PathVariable @NotBlank String id) {
        SemanticModel model = modelService.get(id);
        modelService.delete(id, model.getRevision());
        return R.ok();
    }

    @GetMapping("/models/{id}/versions")
    @PreAuthorize("@authorizationService.hasPermission('semantic:model:view')")
    public R<List<SemanticVersionVO>> versions(@PathVariable @NotBlank String id) {
        return R.ok(versionService.list(id).stream().map(mapper::toVersion).toList());
    }

    @PostMapping("/models/{id}/versions")
    @PreAuthorize("@authorizationService.hasPermission('semantic:model:manage')")
    public R<SemanticVersionVO> createVersion(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody(required = false) SemanticVersionCreateRequest request) {
        String sourceSnapshotId = request == null ? null : request.sourceSnapshotId();
        return R.ok(mapper.toVersion(versionService.createDraft(id, sourceSnapshotId)));
    }

    @GetMapping("/versions/{id}")
    @PreAuthorize("@authorizationService.hasPermission('semantic:model:view')")
    public R<SemanticVersionVO> getVersion(@PathVariable @NotBlank String id) {
        return R.ok(mapper.toVersion(versionService.get(id)));
    }

    private SemanticModelVO toModel(SemanticModel model) {
        SemanticVersion activeVersion = model.getActiveVersionId() == null
                ? null
                : versionService.get(model.getId(), model.getActiveVersionId());
        return mapper.toModel(model, activeVersion);
    }

    private SemanticModelStatus parseStatus(String status) {
        return status == null || status.isBlank()
                ? null
                : SemanticModelStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
    }
}
