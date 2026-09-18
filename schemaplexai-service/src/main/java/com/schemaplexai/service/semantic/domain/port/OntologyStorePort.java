package com.schemaplexai.service.semantic.domain.port;

import com.schemaplexai.service.semantic.domain.model.ontology.GraphQuery;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraph;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyNodeUpdate;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologySubgraph;

/** 本体 asserted 图的写入和受限邻域读取端口。 */
public interface OntologyStorePort {

    void replaceDraft(OntologyGraph graph);

    void updateNode(OntologyGraphRef ref, String nodeIri, OntologyNodeUpdate update);

    OntologySubgraph querySubgraph(OntologyGraphRef ref, GraphQuery query);
}
