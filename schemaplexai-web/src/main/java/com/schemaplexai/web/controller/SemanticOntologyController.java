package com.schemaplexai.web.controller;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.R;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.model.dto.semantic.SemanticNodeUpdateRequest;
import com.schemaplexai.model.dto.semantic.SemanticVersionActionRequest;
import com.schemaplexai.model.vo.semantic.SemanticGraphVO;
import com.schemaplexai.model.vo.semantic.SemanticNodeUpdateVO;
import com.schemaplexai.model.vo.semantic.SemanticPublishVO;
import com.schemaplexai.model.vo.semantic.SemanticValidationVO;
import com.schemaplexai.service.semantic.application.orchestration.OntologyGraphApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.SemanticPublishApplicationService;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.model.ontology.GraphQuery;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyNodeUpdate;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologySubgraph;
import com.schemaplexai.service.semantic.domain.model.ontology.SemanticValidation;
import com.schemaplexai.web.mapper.SemanticWebMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 本体图编辑、校验和发布 REST 入口。 */
@Validated
@RestController
@RequestMapping("/semantic/versions")
@RequiredArgsConstructor
public class SemanticOntologyController {

    private final OntologyGraphApplicationService graphService;
    private final SemanticPublishApplicationService publishService;
    private final SemanticWebMapper mapper;

    @GetMapping("/{id}/graph")
    @PreAuthorize("@authorizationService.hasPermission('semantic:model:view')")
    public R<SemanticGraphVO> graph(
            @PathVariable @NotBlank String id,
            @RequestParam(required = false) String focusNodeId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String nodeKind,
            @RequestParam(defaultValue = "1") @Min(0) @Max(2) int depth,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int size) {
        try {
            OntologySubgraph graph = graphService.query(
                    id, new GraphQuery(focusNodeId, keyword, depth, page, size));
            return R.ok(filterKind(mapper.toGraph(graph, focusNodeId), nodeKind));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST, exception.getMessage());
        }
    }

    @PutMapping("/{id}/nodes/{nodeId}")
    @PreAuthorize("@authorizationService.hasPermission('semantic:model:manage')")
    public R<SemanticNodeUpdateVO> updateNode(
            @PathVariable @NotBlank String id,
            @PathVariable @NotBlank String nodeId,
            @Valid @RequestBody SemanticNodeUpdateRequest request) {
        SemanticVersion version = graphService.updateNode(
                id,
                nodeId,
                toNodeUpdate(request),
                request.expectedRevision());
        SemanticGraphVO graph = mapper.toGraph(
                graphService.query(id, new GraphQuery(nodeId, null, 0, 1, 1)),
                nodeId);
        SemanticGraphVO.Node node = graph.nodes().stream()
                .filter(candidate -> candidate.id().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_NODE_NOT_FOUND));
        return R.ok(new SemanticNodeUpdateVO(id, version.getRevision(), node));
    }

    @PostMapping("/{id}/validate")
    @PreAuthorize("@authorizationService.hasPermission('semantic:model:manage')")
    public R<SemanticValidationVO> validate(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody SemanticVersionActionRequest request) {
        SemanticValidation validation = publishService.validate(id, request.expectedRevision());
        return R.ok(mapper.toValidation(id, request.expectedRevision(), validation));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("@authorizationService.hasPermission('semantic:model:manage')")
    public R<SemanticPublishVO> publish(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody SemanticVersionActionRequest request) {
        SemanticVersion version = publishService.publish(id, request.expectedRevision());
        return R.ok(new SemanticPublishVO(
                version.getModelId(),
                mapper.toVersion(version),
                "PUBLISHED".equals(version.getStatus().name())));
    }

    private OntologyNodeUpdate toNodeUpdate(SemanticNodeUpdateRequest request) {
        SemanticNodeUpdateRequest.PhysicalMappingRequest mapping = request.mapping();
        return new OntologyNodeUpdate(
                request.name(),
                request.label(),
                request.description(),
                request.synonyms(),
                request.dataType(),
                request.required(),
                mapping == null ? null : new OntologyNodeUpdate.PhysicalMapping(
                        mapping.sourceId(),
                        mapping.physicalObject(),
                        mapping.physicalField(),
                        mapping.mappingKind()));
    }

    private SemanticGraphVO filterKind(SemanticGraphVO graph, String nodeKind) {
        if (nodeKind == null || nodeKind.isBlank()) {
            return graph;
        }
        var nodes = graph.nodes().stream().filter(node -> node.kind().equals(nodeKind)).toList();
        var nodeIds = nodes.stream().map(SemanticGraphVO.Node::id).collect(java.util.stream.Collectors.toSet());
        var edges = graph.edges().stream()
                .filter(edge -> nodeIds.contains(edge.source()) && nodeIds.contains(edge.target()))
                .toList();
        return new SemanticGraphVO(nodes, edges, nodes.size(), graph.hasMore(), graph.focusNodeId());
    }
}
