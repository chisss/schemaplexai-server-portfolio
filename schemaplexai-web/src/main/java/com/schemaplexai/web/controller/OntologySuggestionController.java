package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.semantic.OntologySuggestionRequest;
import com.schemaplexai.service.semantic.application.orchestration.OntologySuggestionApplicationService;
import com.schemaplexai.service.semantic.domain.port.OntologySuggestionPort.Suggestion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 本体配置助手入口。 */
@Validated
@RestController
@RequestMapping("/semantic/versions/{id}/configuration/suggestions")
@RequiredArgsConstructor
public class OntologySuggestionController {

    private final OntologySuggestionApplicationService suggestionService;

    @PostMapping
    @PreAuthorize("@authorizationService.hasPermission('semantic:model:manage')")
    public R<Suggestion> suggest(@PathVariable @NotBlank String id,
                                 @Valid @RequestBody OntologySuggestionRequest request) {
        return R.ok(suggestionService.suggest(id, request.expectedRevision(), request.goal(), request.selectedObjects()));
    }
}
