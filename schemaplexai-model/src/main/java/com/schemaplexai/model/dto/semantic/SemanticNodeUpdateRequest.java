package com.schemaplexai.model.dto.semantic;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 编辑本体节点请求。 */
public record SemanticNodeUpdateRequest(
        @NotBlank(message = "节点名称不能为空")
        @Size(max = 200, message = "节点名称不能超过200个字符")
        String name,
        @Size(max = 200, message = "节点标签不能超过200个字符") String label,
        @Size(max = 2000, message = "节点描述不能超过2000个字符") String description,
        @Size(max = 50, message = "同义词不能超过50个") List<@Size(max = 200) String> synonyms,
        @Size(max = 512, message = "数据类型不能超过512个字符") String dataType,
        Boolean required,
        @Valid PhysicalMappingRequest mapping,
        @Min(value = 0, message = "revision不能小于0") long expectedRevision) {

    /** 物理字段映射请求。 */
    public record PhysicalMappingRequest(
            @NotBlank(message = "数据源不能为空") String sourceId,
            @NotBlank(message = "物理对象不能为空")
            @Size(max = 512, message = "物理对象不能超过512个字符") String physicalObject,
            @Size(max = 512, message = "物理字段不能超过512个字符") String physicalField,
            @NotBlank(message = "映射类型不能为空")
            @Pattern(regexp = "table|column|collection|field", message = "映射类型不支持")
            String mappingKind) {
    }
}
