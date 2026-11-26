package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.service.semantic.application.orchestration.OntologyConfigurationApplicationService;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyArtifacts;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfigurationWorkspace;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 结构化本体配置工作台和已保存版本产物。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/semantic/versions/{id}/configuration")
public class OntologyConfigurationController {

    private final OntologyConfigurationApplicationService configurationService;

    @GetMapping
    @PreAuthorize("@authorizationService.hasPermission('semantic:model:view')")
    public R<OntologyConfigurationWorkspace> get(@PathVariable @NotBlank String id) {
        return R.ok(configurationService.load(id));
    }

    @PutMapping
    @PreAuthorize("@authorizationService.hasPermission('semantic:model:manage')")
    public R<OntologyConfigurationWorkspace> save(
            @PathVariable @NotBlank String id, @Valid @RequestBody SaveRequest request) {
        return R.ok(configurationService.save(id, request.expectedRevision(), request.configuration()));
    }

    @GetMapping("/artifacts")
    @PreAuthorize("@authorizationService.hasPermission('semantic:model:view')")
    public R<OntologyArtifacts> artifacts(@PathVariable @NotBlank String id) {
        return R.ok(configurationService.artifacts(id));
    }

    public record SaveRequest(
            @NotNull @Min(0) Long expectedRevision, @NotNull OntologyConfiguration configuration) {
    }
}
