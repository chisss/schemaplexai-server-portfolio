package com.schemaplexai.service.semantic.domain.service;

import com.schemaplexai.service.semantic.domain.model.ontology.OntologyStatement;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyTerm;

import java.util.List;

import static com.schemaplexai.service.semantic.common.SemanticVocabulary.RDF_TYPE;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.RDFS_LABEL;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.SHACL_MIN_COUNT;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.SHACL_NODE_SHAPE;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.SHACL_PATH;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.SHACL_PROPERTY;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.SHACL_TARGET_SUBJECTS_OF;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.XSD_INTEGER;

/** Phase 1 服务端基线约束，客户端不能提交或覆盖这些规则。 */
public final class BaselineSemanticShapes {

    private static final String RESOURCE_SHAPE = "urn:schemaplexai:shape:typed-resource";
    private static final String LABEL_SHAPE = "urn:schemaplexai:shape:typed-resource:label";

    public List<OntologyStatement> statements() {
        return List.of(
                iri(RESOURCE_SHAPE, RDF_TYPE, SHACL_NODE_SHAPE),
                iri(RESOURCE_SHAPE, SHACL_TARGET_SUBJECTS_OF, RDF_TYPE),
                iri(RESOURCE_SHAPE, SHACL_PROPERTY, LABEL_SHAPE),
                iri(LABEL_SHAPE, SHACL_PATH, RDFS_LABEL),
                new OntologyStatement(
                        LABEL_SHAPE,
                        SHACL_MIN_COUNT,
                        OntologyTerm.typedLiteral("1", XSD_INTEGER)));
    }

    private OntologyStatement iri(String subject, String predicate, String object) {
        return new OntologyStatement(subject, predicate, OntologyTerm.iri(object));
    }
}
