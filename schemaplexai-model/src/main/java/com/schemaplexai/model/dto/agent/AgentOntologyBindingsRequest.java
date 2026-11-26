package com.schemaplexai.model.dto.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 替换 Agent 的本体绑定。 */
public record AgentOntologyBindingsRequest(
        @NotNull @PositiveOrZero Long expectedRevision,
        @NotNull @Size(max = 4) List<@NotNull @Valid AgentOntologyBinding> bindings) { }
