package com.schemaplexai.service.semantic.infrastructure.jena;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** 创建只读的租户语义图视图，调用方不能通过该视图访问其他图。 */
@Component
@RequiredArgsConstructor
public class TenantDatasetViewFactory {

    private final SemanticGraphIriFactory graphIriFactory;

    public Dataset createReadOnlyView(
            DatasetGraph datasetGraph,
            String tenantId,
            String modelId,
            long version) {
        if (datasetGraph == null) {
            throw new IllegalArgumentException("datasetGraph is required");
        }
        SemanticGraphIriFactory.GraphSet graphSet = graphIriFactory.create(tenantId, modelId, version);
        Set<Node> allowedGraphs = Set.of(
                NodeFactory.createURI(graphSet.asserted()),
                NodeFactory.createURI(graphSet.shapes()),
                NodeFactory.createURI(graphSet.inferred()));
        DatasetGraph view = new TenantReadOnlyDatasetGraph(datasetGraph, List.copyOf(allowedGraphs));
        return DatasetFactory.wrap(view);
    }
}
