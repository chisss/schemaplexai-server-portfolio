package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.agent.AgentOntologyBindingsRequest;
import com.schemaplexai.service.agent.ontology.AgentOntologyBindingService;
import com.schemaplexai.service.agent.ontology.AgentOntologyBindingService.Bindings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent 业务本体绑定的管理入口。 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/agents/{id}/ontology-bindings")
@PreAuthorize("@authorizationService.hasPermission('agent:manage') and @authorizationService.hasPermission('semantic:model:view')")
public class AgentOntologyBindingController {

    private final AgentOntologyBindingService bindings;

    @GetMapping
    public R<Bindings> get(@PathVariable @NotBlank String id) {
        return R.ok(bindings.get(id));
    }

    @PutMapping
    public R<Bindings> replace(@PathVariable @NotBlank String id, @Valid @RequestBody AgentOntologyBindingsRequest request) {
        return R.ok(bindings.replace(id, request));
    }
}
