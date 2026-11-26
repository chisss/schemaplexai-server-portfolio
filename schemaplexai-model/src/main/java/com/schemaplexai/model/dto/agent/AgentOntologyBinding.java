package com.schemaplexai.model.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 指定业务本体，并可固定一个已发布版本。 */
public record AgentOntologyBinding(
        @NotBlank @Size(max = 36) String modelId,
        @Size(max = 36) String versionId) { }
