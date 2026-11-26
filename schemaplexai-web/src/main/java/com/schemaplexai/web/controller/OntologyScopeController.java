package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.service.semantic.application.orchestration.OntologyScopeApplicationService;
import com.schemaplexai.service.semantic.domain.model.scope.OntologyScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/semantic/scopes")
@RequiredArgsConstructor
public class OntologyScopeController {

    private final OntologyScopeApplicationService service;

    public record CreateRequest(@NotBlank @Size(max = 100) String systemName,
                                @NotBlank @Size(max = 100) String domainName) {
    }

    @GetMapping
    @PreAuthorize("@authorizationService.hasPermission('semantic:model:view')")
    public R<List<OntologyScope>> list() {
        return R.ok(service.list());
    }

    @PostMapping
    @PreAuthorize("@authorizationService.hasPermission('semantic:model:manage')")
    public R<OntologyScope> create(@Valid @RequestBody CreateRequest request) {
        return R.ok(service.create(request.systemName(), request.domainName()));
    }
}
