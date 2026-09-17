package com.schemaplexai.service.semantic.domain.port;

import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyStatement;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishPreparation;

import java.util.List;

/** 语义图校验、受限推理和发布端口。 */
public interface SemanticPublishPort {

    PublishPreparation prepare(
            OntologyGraphRef ref,
            List<OntologyStatement> shapes,
            String operationId);

    void commit(PublishPreparation preparation);

    void discard(PublishPreparation preparation);
}
