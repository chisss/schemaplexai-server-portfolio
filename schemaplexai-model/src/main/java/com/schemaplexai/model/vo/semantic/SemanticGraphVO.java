package com.schemaplexai.model.vo.semantic;

import java.util.List;

/** 前端本体工作台使用的分页邻域图。 */
public record SemanticGraphVO(
        List<Node> nodes,
        List<Edge> edges,
        long totalNodes,
        boolean hasMore,
        String focusNodeId) {

    /** 图节点视图。 */
    public record Node(
            String id,
            String iri,
            String name,
            String label,
            String description,
            String kind,
            List<String> synonyms,
            String dataType,
            Boolean required,
            Mapping mapping) {
    }

    /** 图关系视图。 */
    public record Edge(
            String id,
            String source,
            String target,
            String predicate,
            String label,
            boolean inferred) {
    }

    /** 节点物理映射视图。 */
    public record Mapping(
            String id,
            String sourceId,
            String semanticIri,
            String physicalObject,
            String physicalField,
            String mappingKind) {
    }
}
