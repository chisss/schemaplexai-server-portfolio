package com.schemaplexai.service.semantic.infrastructure.jena;

import com.schemaplexai.service.semantic.domain.model.ontology.OntologyStatement;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyTerm;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.Quad;
import org.springframework.stereotype.Component;

/** 领域三元组与 Jena Node 的边界映射。 */
@Component
class JenaOntologyStatementMapper {

    Quad toQuad(Node graphNode, OntologyStatement statement) {
        return new Quad(
                graphNode,
                NodeFactory.createURI(statement.subjectIri()),
                NodeFactory.createURI(statement.predicateIri()),
                toNode(statement.object()));
    }

    OntologyStatement fromQuad(Quad quad) {
        return new OntologyStatement(
                quad.getSubject().getURI(),
                quad.getPredicate().getURI(),
                fromNode(quad.getObject()));
    }

    private Node toNode(OntologyTerm term) {
        if (term.kind() == OntologyTerm.Kind.IRI) {
            return NodeFactory.createURI(term.value());
        }
        if (term.language() != null) {
            return NodeFactory.createLiteralLang(term.value(), term.language());
        }
        if (term.datatype() != null) {
            return NodeFactory.createLiteralDT(
                    term.value(), TypeMapper.getInstance().getSafeTypeByName(term.datatype()));
        }
        return NodeFactory.createLiteralString(term.value());
    }

    private OntologyTerm fromNode(Node node) {
        if (node.isURI()) {
            return OntologyTerm.iri(node.getURI());
        }
        if (!node.isLiteral()) {
            throw new IllegalStateException("ontology graph contains unsupported RDF object");
        }
        String language = node.getLiteralLanguage();
        if (language != null && !language.isBlank()) {
            return OntologyTerm.languageLiteral(node.getLiteralLexicalForm(), language);
        }
        String datatype = node.getLiteralDatatypeURI();
        return datatype == null
                ? OntologyTerm.literal(node.getLiteralLexicalForm())
                : OntologyTerm.typedLiteral(node.getLiteralLexicalForm(), datatype);
    }
}
