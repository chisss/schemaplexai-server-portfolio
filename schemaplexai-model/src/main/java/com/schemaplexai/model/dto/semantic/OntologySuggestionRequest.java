package com.schemaplexai.model.dto.semantic;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 生成指定版本的本体配置建议。 */
public record OntologySuggestionRequest(
        @NotNull @PositiveOrZero Long expectedRevision,
        @NotBlank @Size(max = 2000) String goal,
        @NotNull @Size(max = 12) List<@NotBlank @Size(max = 512) String> selectedObjects) { }
